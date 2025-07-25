/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.ozone.om.snapshot;

import static org.apache.hadoop.ozone.OzoneConsts.OM_CHECKPOINT_DIR;

import com.google.common.annotations.VisibleForTesting;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.hadoop.ozone.om.OmSnapshotManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ozone Manager Snapshot Utilities.
 */
public final class OmSnapshotUtils {

  public static final String DATA_PREFIX = "data";
  public static final String DATA_SUFFIX = "txt";
  private static final Logger LOG =
      LoggerFactory.getLogger(OmSnapshotUtils.class);

  private OmSnapshotUtils() { }

  /**
   * Get the filename without the introductory metadata directory.
   *
   * @param truncateLength Length to remove.
   * @param file           File to remove prefix from.
   * @return Truncated string.
   */
  public static String truncateFileName(int truncateLength, Path file) {
    return file.toString().substring(truncateLength);
  }

  /**
   * Get the INode for file.
   *
   * @param file File whose INode is to be retrieved.
   * @return INode for file.
   */
  @VisibleForTesting
  public static Object getINode(Path file) throws IOException {
    return Files.readAttributes(file, BasicFileAttributes.class).fileKey();
  }

  /**
   * Returns a string combining the inode (fileKey) and the last modification time (mtime) of the given file.
   * <p>
   * The returned string is formatted as "{inode}-{mtime}", where:
   * <ul>
   *   <li>{@code inode} is the unique file key obtained from the file system, typically representing
   *   the inode on POSIX systems</li>
   *   <li>{@code mtime} is the last modified time of the file in milliseconds since the epoch</li>
   * </ul>
   *
   * @param file the {@link Path} to the file whose inode and modification time are to be retrieved
   * @return a string in the format "{inode}-{mtime}"
   * @throws IOException if an I/O error occurs
   */
  public static String getFileInodeAndLastModifiedTimeString(Path file) throws IOException {
    Object inode = Files.readAttributes(file, BasicFileAttributes.class).fileKey();
    FileTime mTime = Files.getLastModifiedTime(file);
    return String.format("%s-%s", inode, mTime.toMillis());
  }

  /**
   * Create file of links to add to tarball.
   * Format of entries are either:
   * dir1/fileTo fileFrom
   * for files in active db or:
   * dir1/fileTo dir2/fileFrom
   * for files in another directory, (either another snapshot dir or
   * sst compaction backup directory)
   *
   * @param truncateLength - Length of initial path to trim in file path.
   * @param hardLinkFiles  - Map of link-&gt;file paths.
   * @return Path to the file of links created.
   */
  public static Path createHardLinkList(int truncateLength,
                                        Map<Path, Path> hardLinkFiles)
      throws IOException {
    Path data = Files.createTempFile(DATA_PREFIX, DATA_SUFFIX);
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<Path, Path> entry : hardLinkFiles.entrySet()) {
      String fixedFile = truncateFileName(truncateLength, entry.getValue());
      // If this file is from the active db, strip the path.
      if (fixedFile.startsWith(OM_CHECKPOINT_DIR)) {
        Path f = Paths.get(fixedFile).getFileName();
        if (f != null) {
          fixedFile = f.toString();
        }
      }
      sb.append(truncateFileName(truncateLength, entry.getKey())).append('\t')
          .append(fixedFile).append('\n');
    }
    Files.write(data, sb.toString().getBytes(StandardCharsets.UTF_8));
    return data;
  }

  /**
   * Create hard links listed in OM_HARDLINK_FILE.
   *
   * @param dbPath Path to db to have links created.
   * @param deleteSourceFiles - Whether to delete the source files after creating the links.
   */
  public static void createHardLinks(Path dbPath, boolean deleteSourceFiles) throws IOException {
    File hardLinkFile =
        new File(dbPath.toString(), OmSnapshotManager.OM_HARDLINK_FILE);
    List<Path> filesToDelete = new ArrayList<>();
    if (hardLinkFile.exists()) {
      // Read file.
      try (Stream<String> s = Files.lines(hardLinkFile.toPath())) {
        List<String> lines = s.collect(Collectors.toList());

        // Create a link for each line.
        for (String l : lines) {
          String[] parts = l.split("\t");
          if (parts.length != 2) {
            LOG.warn("Skipping malformed line in hardlink file: {}", l);
            continue;
          }
          String from = parts[1];
          String to = parts[0];
          Path fullFromPath = Paths.get(dbPath.toString(), from);
          filesToDelete.add(fullFromPath);
          Path fullToPath = Paths.get(dbPath.toString(), to);
          // Make parent dir if it doesn't exist.
          Path parent = fullToPath.getParent();
          if ((parent != null) && (!parent.toFile().exists())) {
            if (!parent.toFile().mkdirs()) {
              throw new IOException(
                  "Failed to create directory: " + parent.toString());
            }
          }
          Files.createLink(fullToPath, fullFromPath);
        }
        if (!hardLinkFile.delete()) {
          throw new IOException("Failed to delete: " + hardLinkFile);
        }
      }
    }
    if (deleteSourceFiles) {
      for (Path fileToDelete : filesToDelete) {
        try {
          Files.deleteIfExists(fileToDelete);
        } catch (IOException e) {
          LOG.warn("Couldn't delete source file {} while unpacking the DB", fileToDelete, e);
        }
      }
    }
  }

  /**
   * Link each of the files in oldDir to newDir.
   *
   * @param oldDir The dir to create links from.
   * @param newDir The dir to create links to.
   */
  public static void linkFiles(File oldDir, File newDir) throws IOException {
    int truncateLength = oldDir.toString().length() + 1;
    List<String> oldDirList;
    try (Stream<Path> files = Files.walk(oldDir.toPath())) {
      oldDirList = files.map(Path::toString).
          // Don't copy the directory itself
          filter(s -> !s.equals(oldDir.toString())).
          // Remove the old path
          map(s -> s.substring(truncateLength)).
          sorted().
          collect(Collectors.toList());
    }
    for (String s: oldDirList) {
      File oldFile = new File(oldDir, s);
      File newFile = new File(newDir, s);
      File newParent = newFile.getParentFile();
      if (!newParent.exists()) {
        if (!newParent.mkdirs()) {
          throw new IOException("Directory create fails: " + newParent);
        }
      }
      if (oldFile.isDirectory()) {
        if (!newFile.mkdirs()) {
          throw new IOException("Directory create fails: " + newFile);
        }
      } else {
        Files.createLink(newFile.toPath(), oldFile.toPath());
      }
    }
  }
}
