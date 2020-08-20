/*
 * Portions Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.facebook.buck.skylark.io.impl;

import com.facebook.buck.core.filesystems.AbsPath;
import com.google.common.base.Joiner;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.base.Splitter;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.ForwardingListenableFuture;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.common.util.concurrent.Uninterruptibles;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/**
 * Implementation of a subset of UNIX-style file globbing, expanding "*" and "?" as wildcards, but
 * not [a-z] ranges.
 *
 * <p><code>**</code> gets special treatment in include patterns. If it is used as a complete path
 * segment it matches the filenames in subdirectories recursively.
 *
 * <p>Importantly, note that the glob matches are in an unspecified order.
 */
final class UnixGlob {
  private UnixGlob() {}

  @Nullable
  private static BasicFileAttributes statIfFound(AbsPath path) throws IOException {
    try {
      return Files.readAttributes(path.getPath(), BasicFileAttributes.class);
    } catch (FileNotFoundException | FileSystemException e) {
      // Catch these two exception because that's what Bazel VFS did before we forked it.
      return null;
    }
  }

  private static List<AbsPath> globInternalUninterruptible(
      AbsPath base,
      Collection<String> patterns,
      boolean excludeDirectories,
      Predicate<AbsPath> dirPred,
      Executor executor)
      throws IOException {
    GlobVisitor visitor = new GlobVisitor(executor);
    return visitor.globUninterruptible(base, patterns, excludeDirectories, dirPred);
  }

  /**
   * Checks that each pattern is valid, splits it into segments and checks that each segment
   * contains only valid wildcards.
   *
   * @return list of segment arrays
   */
  private static List<String[]> checkAndSplitPatterns(Collection<String> patterns) {
    List<String[]> list = Lists.newArrayListWithCapacity(patterns.size());
    for (String pattern : patterns) {
      String error = checkPatternForError(pattern);
      if (error != null) {
        throw new IllegalArgumentException(error + " (in glob pattern '" + pattern + "')");
      }
      Iterable<String> segments = Splitter.on('/').split(pattern);
      list.add(Iterables.toArray(segments, String.class));
    }
    return list;
  }

  /** @return whether or not {@code pattern} contains illegal characters */
  static String checkPatternForError(String pattern) {
    if (pattern.isEmpty()) {
      return "pattern cannot be empty";
    }
    if (pattern.charAt(0) == '/') {
      return "pattern cannot be absolute";
    }
    Iterable<String> segments = Splitter.on('/').split(pattern);
    for (String segment : segments) {
      if (segment.isEmpty()) {
        return "empty segment not permitted";
      }
      if (segment.equals(".") || segment.equals("..")) {
        return "segment '" + segment + "' not permitted";
      }
      if (segment.contains("**") && !segment.equals("**")) {
        return "recursive wildcard must be its own segment";
      }
    }
    return null;
  }

  /**
   * Returns whether {@code str} matches the glob pattern {@code pattern}. This method may use the
   * {@code patternCache} to speed up the matching process.
   *
   * @param pattern a glob pattern
   * @param str the string to match
   * @param patternCache a cache from patterns to compiled Pattern objects, or {@code null} to skip
   *     caching
   */
  private static boolean matches(String pattern, String str, Map<String, Pattern> patternCache) {
    if (pattern.length() == 0 || str.length() == 0) {
      return false;
    }

    // Common case: **
    if (pattern.equals("**")) {
      return true;
    }

    // Common case: *
    if (pattern.equals("*")) {
      return true;
    }

    // If a filename starts with '.', this char must be matched explicitly.
    if (str.charAt(0) == '.' && pattern.charAt(0) != '.') {
      return false;
    }

    // Common case: *.xyz
    if (pattern.charAt(0) == '*' && pattern.lastIndexOf('*') == 0) {
      return str.endsWith(pattern.substring(1));
    }
    // Common case: xyz*
    int lastIndex = pattern.length() - 1;
    // The first clause of this if statement is unnecessary, but is an
    // optimization--charAt runs faster than indexOf.
    if (pattern.charAt(lastIndex) == '*' && pattern.indexOf('*') == lastIndex) {
      return str.startsWith(pattern.substring(0, lastIndex));
    }

    Pattern regex =
        patternCache == null
            ? makePatternFromWildcard(pattern)
            : patternCache.computeIfAbsent(pattern, p -> makePatternFromWildcard(p));
    return regex.matcher(str).matches();
  }

  /**
   * Returns a regular expression implementing a matcher for "pattern", in which "*" and "?" are
   * wildcards.
   *
   * <p>e.g. "foo*bar?.java" -> "foo.*bar.\\.java"
   */
  private static Pattern makePatternFromWildcard(String pattern) {
    StringBuilder regexp = new StringBuilder();
    for (int i = 0, len = pattern.length(); i < len; i++) {
      char c = pattern.charAt(i);
      switch (c) {
        case '*':
          int toIncrement = 0;
          if (len > i + 1 && pattern.charAt(i + 1) == '*') {
            // The pattern '**' is interpreted to match 0 or more directory separators, not 1 or
            // more. We skip the next * and then find a trailing/leading '/' and get rid of it.
            toIncrement = 1;
            if (len > i + 2 && pattern.charAt(i + 2) == '/') {
              // We have '**/' -- skip the '/'.
              toIncrement = 2;
            } else if (len == i + 2 && i > 0 && pattern.charAt(i - 1) == '/') {
              // We have '/**' -- remove the '/'.
              regexp.delete(regexp.length() - 1, regexp.length());
            }
          }
          regexp.append(".*");
          i += toIncrement;
          break;
        case '?':
          regexp.append('.');
          break;
          // escape the regexp special characters that are allowed in wildcards
        case '^':
        case '$':
        case '|':
        case '+':
        case '{':
        case '}':
        case '[':
        case ']':
        case '\\':
        case '.':
          regexp.append('\\');
          regexp.append(c);
          break;
        default:
          regexp.append(c);
          break;
      }
    }
    return Pattern.compile(regexp.toString());
  }

  public static Builder forPath(AbsPath path) {
    return new Builder(path);
  }

  /** Builder class for UnixGlob. */
  public static class Builder {
    private AbsPath base;
    private List<String> patterns;
    private boolean excludeDirectories;
    private Predicate<AbsPath> pathFilter;
    private Executor executor;

    /** Creates a glob builder with the given base path. */
    public Builder(AbsPath base) {
      this.base = base;
      this.patterns = Lists.newArrayList();
      this.excludeDirectories = false;
      this.pathFilter = Predicates.alwaysTrue();
    }

    /**
     * Adds a pattern to include to the glob builder.
     *
     * <p>For a description of the syntax of the patterns, see {@link UnixGlob}.
     */
    public Builder addPatterns(Collection<String> patterns) {
      this.patterns.addAll(patterns);
      return this;
    }

    /** If set to true, directories are not returned in the glob result. */
    public Builder setExcludeDirectories(boolean excludeDirectories) {
      this.excludeDirectories = excludeDirectories;
      return this;
    }

    /**
     * Sets the executor to use for parallel glob evaluation. If unset, evaluation is done
     * in-thread.
     */
    public Builder setExecutor(Executor pool) {
      this.executor = pool;
      return this;
    }

    /** Executes the glob. */
    public List<AbsPath> glob() throws IOException {
      return globInternalUninterruptible(base, patterns, excludeDirectories, pathFilter, executor)
          .stream()
          .map(p -> AbsPath.of(base.getFileSystem().getPath(p.toString())))
          .collect(ImmutableList.toImmutableList());
    }
  }

  /** Adapts the result of the glob visitation as a Future. */
  private static class GlobFuture extends ForwardingListenableFuture<List<AbsPath>> {
    private final GlobVisitor visitor;
    private final SettableFuture<List<AbsPath>> delegate = SettableFuture.create();

    public GlobFuture(GlobVisitor visitor) {
      this.visitor = visitor;
    }

    @Override
    protected ListenableFuture<List<AbsPath>> delegate() {
      return delegate;
    }

    public void setException(Throwable throwable) {
      delegate.setException(throwable);
    }

    public void set(List<AbsPath> paths) {
      delegate.set(paths);
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
      // Best-effort interrupt of the in-flight visitation.
      visitor.cancel();
      return true;
    }

    public void markCanceled() {
      super.cancel(true);
    }
  }

  /**
   * GlobVisitor executes a glob using parallelism, which is useful when the glob() requires many
   * readdir() calls on high latency filesystems.
   */
  private static final class GlobVisitor {
    // These collections are used across workers and must therefore be thread-safe.
    private final Collection<AbsPath> results = Sets.newConcurrentHashSet();
    private final ConcurrentHashMap<String, Pattern> cache = new ConcurrentHashMap<>();

    private final GlobFuture result;
    private final Executor executor;
    private final AtomicLong totalOps = new AtomicLong(0);
    private final AtomicLong pendingOps = new AtomicLong(0);
    private final AtomicReference<IOException> ioException = new AtomicReference<>();
    private final AtomicReference<RuntimeException> runtimeException = new AtomicReference<>();
    private final AtomicReference<Error> error = new AtomicReference<>();
    private volatile boolean canceled = false;

    GlobVisitor(Executor executor) {
      this.executor = executor;
      this.result = new GlobFuture(this);
    }

    List<AbsPath> globUninterruptible(
        AbsPath base,
        Collection<String> patterns,
        boolean excludeDirectories,
        Predicate<AbsPath> dirPred)
        throws IOException {
      try {
        return Uninterruptibles.getUninterruptibly(
            globAsync(base, patterns, excludeDirectories, dirPred));
      } catch (ExecutionException e) {
        Throwable cause = e.getCause();
        Throwables.propagateIfPossible(cause, IOException.class);
        throw new RuntimeException(e);
      }
    }

    private static boolean isRecursivePattern(String pattern) {
      return "**".equals(pattern);
    }

    Future<List<AbsPath>> globAsync(
        AbsPath base,
        Collection<String> patterns,
        boolean excludeDirectories,
        Predicate<AbsPath> dirPred) {

      BasicFileAttributes baseStat;
      try {
        baseStat = statIfFound(base);
      } catch (IOException e) {
        return Futures.immediateFailedFuture(e);
      }
      if (baseStat == null || patterns.isEmpty()) {
        return Futures.immediateFuture(Collections.emptyList());
      }

      List<String[]> splitPatterns = checkAndSplitPatterns(patterns);

      // We do a dumb loop, even though it will likely duplicate logical work (note that the
      // physical filesystem operations are cached). In order to optimize, we would need to keep
      // track of which patterns shared sub-patterns and which did not (for example consider the
      // glob [*/*.java, sub/*.java, */*.txt]).
      pendingOps.incrementAndGet();
      try {
        for (String[] splitPattern : splitPatterns) {
          int numRecursivePatterns = 0;
          for (String pattern : splitPattern) {
            if (isRecursivePattern(pattern)) {
              ++numRecursivePatterns;
            }
          }
          GlobTaskContext context =
              numRecursivePatterns > 1
                  ? new RecursiveGlobTaskContext(splitPattern, excludeDirectories, dirPred)
                  : new GlobTaskContext(splitPattern, excludeDirectories, dirPred);
          context.queueGlob(base, baseStat.isDirectory(), 0);
        }
      } finally {
        decrementAndCheckDone();
      }

      return result;
    }

    private Throwable getMostSeriousThrowableSoFar() {
      if (error.get() != null) {
        return error.get();
      }
      if (runtimeException.get() != null) {
        return runtimeException.get();
      }
      if (ioException.get() != null) {
        return ioException.get();
      }
      return null;
    }

    /** Should only be called by link {@GlobTaskContext}. */
    private void queueGlob(
        final AbsPath base, final boolean baseIsDir, final int idx, final GlobTaskContext context) {
      enqueue(
          new Runnable() {
            @Override
            public void run() {
              try (SilentCloseable c =
                  Profiler.instance().profile(ProfilerTask.VFS_GLOB, base.toString())) {
                reallyGlob(base, baseIsDir, idx, context);
              } catch (IOException e) {
                ioException.set(e);
              } catch (RuntimeException e) {
                runtimeException.set(e);
              } catch (Error e) {
                error.set(e);
              }
            }

            @Override
            public String toString() {
              return String.format(
                  "%s glob(include=[%s], exclude_directories=%s)",
                  base,
                  "\"" + Joiner.on("\", \"").join(context.patternParts) + "\"",
                  context.excludeDirectories);
            }
          });
    }

    /** Should only be called by link {@GlobTaskContext}. */
    private void queueTask(Runnable runnable) {
      enqueue(runnable);
    }

    protected void enqueue(final Runnable r) {
      totalOps.incrementAndGet();
      pendingOps.incrementAndGet();

      Runnable wrapped =
          () -> {
            try {
              if (!canceled && getMostSeriousThrowableSoFar() == null) {
                r.run();
              }
            } finally {
              decrementAndCheckDone();
            }
          };

      if (executor == null) {
        wrapped.run();
      } else {
        executor.execute(wrapped);
      }
    }

    protected void cancel() {
      this.canceled = true;
    }

    private void decrementAndCheckDone() {
      if (pendingOps.decrementAndGet() == 0) {
        // We get to 0 iff we are done all the relevant work. This is because we always increment
        // the pending ops count as we're enqueuing, and don't decrement until the task is complete
        // (which includes accounting for any additional tasks that one enqueues).

        Throwable mostSeriousThrowable = getMostSeriousThrowableSoFar();
        if (canceled) {
          result.markCanceled();
        } else if (mostSeriousThrowable != null) {
          result.setException(mostSeriousThrowable);
        } else {
          result.set(ImmutableList.copyOf(results));
        }
      }
    }

    /** A context for evaluating all the subtasks of a single top-level glob task. */
    private class GlobTaskContext {
      private final String[] patternParts;
      private final boolean excludeDirectories;
      private final Predicate<AbsPath> dirPred;

      GlobTaskContext(
          String[] patternParts, boolean excludeDirectories, Predicate<AbsPath> dirPred) {
        this.patternParts = patternParts;
        this.excludeDirectories = excludeDirectories;
        this.dirPred = dirPred;
      }

      protected void queueGlob(AbsPath base, boolean baseIsDir, int patternIdx) {
        GlobVisitor.this.queueGlob(base, baseIsDir, patternIdx, this);
      }

      protected void queueTask(Runnable runnable) {
        GlobVisitor.this.queueTask(runnable);
      }
    }

    /**
     * A special implementation of {@link GlobTaskContext} that dedupes glob subtasks. Our naive
     * implementation of recursive patterns means there are multiple ways to enqueue the same
     * logical subtask.
     */
    private class RecursiveGlobTaskContext extends GlobTaskContext {

      private class GlobTask {
        private final AbsPath base;
        private final int patternIdx;

        private GlobTask(AbsPath base, int patternIdx) {
          this.base = base;
          this.patternIdx = patternIdx;
        }

        @Override
        public boolean equals(Object obj) {
          if (!(obj instanceof GlobTask)) {
            return false;
          }
          GlobTask other = (GlobTask) obj;
          return base.equals(other.base) && patternIdx == other.patternIdx;
        }

        @Override
        public int hashCode() {
          return Objects.hash(base, patternIdx);
        }
      }

      private final Set<GlobTask> visitedGlobSubTasks = Sets.newConcurrentHashSet();

      private RecursiveGlobTaskContext(
          String[] patternParts, boolean excludeDirectories, Predicate<AbsPath> dirPred) {
        super(patternParts, excludeDirectories, dirPred);
      }

      @Override
      protected void queueGlob(AbsPath base, boolean baseIsDir, int patternIdx) {
        if (visitedGlobSubTasks.add(new GlobTask(base, patternIdx))) {
          // This is a unique glob task. For example of how duplicates can arise, consider:
          //   glob(['**/a/**/foo.txt'])
          // with the only file being
          //   a/a/foo.txt
          //
          // there are multiple ways to reach a/a/foo.txt: one route starts by recursively globbing
          // 'a/**/foo.txt' in the base directory of the package, and another route starts by
          // recursively globbing '**/a/**/foo.txt' in subdirectory 'a'.
          super.queueGlob(base, baseIsDir, patternIdx);
        }
      }
    }

    /**
     * Expressed in Haskell:
     *
     * <pre>
     *  reallyGlob base []     = { base }
     *  reallyGlob base [x:xs] = union { reallyGlob(f, xs) | f results "base/x" }
     * </pre>
     */
    private void reallyGlob(AbsPath base, boolean baseIsDir, int idx, GlobTaskContext context)
        throws IOException {
      if (baseIsDir && !context.dirPred.apply(base)) {
        return;
      }

      if (idx == context.patternParts.length) { // Base case.
        if (!(context.excludeDirectories && baseIsDir)) {
          results.add(base);
        }

        return;
      }

      if (!baseIsDir) {
        // Nothing to find here.
        return;
      }

      String pattern = context.patternParts[idx];

      // ** is special: it can match nothing at all.
      // For example, x/** matches x, **/y matches y, and x/**/y matches x/y.
      if (isRecursivePattern(pattern)) {
        context.queueGlob(base, baseIsDir, idx + 1);
      }

      if (!pattern.contains("*") && !pattern.contains("?")) {
        // We do not need to do a readdir in this case, just a stat.
        AbsPath child = base.resolve(pattern);
        BasicFileAttributes status = statIfFound(child);
        if (status == null
            || (!status.isDirectory() && !(status.isRegularFile() || status.isOther()))) {
          // The file is a dangling symlink, fifo, does not exist, etc.
          return;
        }

        context.queueGlob(child, status.isDirectory(), idx + 1);
        return;
      }

      Collection<Path> dents = Files.list(base.getPath()).collect(ImmutableList.toImmutableList());
      for (Path child : dents) {
        BasicFileAttributes attributes = Files.readAttributes(child, BasicFileAttributes.class);
        if (attributes.isOther()) {
          // The file is a special file (fifo, etc.). No need to even match against the pattern.
          continue;
        }
        if (matches(pattern, child.getFileName().toString(), cache)) {
          if (attributes.isSymbolicLink()) {
            processSymlink(AbsPath.of(child), idx, context);
          } else {
            processFileOrDirectory(AbsPath.of(child), Files.isDirectory(child), idx, context);
          }
        }
      }
    }

    /**
     * Process symlinks asynchronously. If we should used readdir(..., Symlinks.FOLLOW), that would
     * result in a sequential symlink resolution with many file system implementations. If the
     * underlying file system is networked and a single directory contains many symlinks, that can
     * lead to substantial slowness.
     */
    private void processSymlink(AbsPath path, int idx, GlobTaskContext context) {
      context.queueTask(
          () -> {
            try {
              BasicFileAttributes status = statIfFound(path);
              if (status != null) {
                processFileOrDirectory(path, status.isDirectory(), idx, context);
              }
            } catch (IOException e) {
              // Intentionally empty. Just ignore symlinks that cannot be stat'ed to leave
              // historical behavior of readdir(..., Symlinks.FOLLOW).
            }
          });
    }

    private void processFileOrDirectory(
        AbsPath path, boolean isDir, int idx, GlobTaskContext context) {
      boolean isRecursivePattern = isRecursivePattern(context.patternParts[idx]);
      if (isDir) {
        context.queueGlob(path, /* baseIsDir= */ true, idx + (isRecursivePattern ? 0 : 1));
      } else if (idx + 1 == context.patternParts.length) {
        results.add(path);
      }
    }
  }
}