// Copyright 2014 Google Inc. All rights reserved.
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

package com.google.devtools.build.xcode.plmerge;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.ByteSource;
import com.google.devtools.build.xcode.common.Platform;
import com.google.devtools.build.xcode.common.TargetDeviceFamily;
import com.google.devtools.build.xcode.util.Equaling;
import com.google.devtools.build.xcode.util.Intersection;
import com.google.devtools.build.xcode.util.Mapping;
import com.google.devtools.build.xcode.util.Value;

import com.dd.plist.BinaryPropertyListWriter;
import com.dd.plist.NSArray;
import com.dd.plist.NSDictionary;
import com.dd.plist.NSObject;
import com.dd.plist.NSString;
import com.dd.plist.PropertyListFormatException;
import com.dd.plist.PropertyListParser;

import org.xml.sax.SAXException;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.parsers.ParserConfigurationException;

/**
 * Utility code for merging project files.
 */
public class PlistMerging extends Value<PlistMerging> {

  /**
   * Exception type thrown when validation of the plist file fails.
   */
  public static class ValidationException extends RuntimeException {
    ValidationException(String message) {
      super(message);
    }
  }

  private final NSDictionary merged;

  @VisibleForTesting
  PlistMerging(NSDictionary merged) {
    super(merged);
    this.merged = merged;
  }

  /**
   * Merges several plist files into a single {@code NSDictionary}. Each file should be a plist (of
   * one of these formats: ASCII, Binary, or XML) that contains an NSDictionary.
   */
  @VisibleForTesting
  static NSDictionary merge(Iterable<? extends Path> sourceFilePaths) throws IOException {
    NSDictionary result = new NSDictionary();
    for (Path sourceFilePath : sourceFilePaths) {
      result.putAll(readPlistFile(sourceFilePath));
    }
    return result;
  }

  @VisibleForTesting
  public static NSDictionary readPlistFile(final Path sourceFilePath) throws IOException {
    ByteSource rawBytes = new Utf8BomSkippingByteSource(sourceFilePath);

    try {
      try (InputStream in = rawBytes.openStream()) {
        return (NSDictionary) PropertyListParser.parse(in);
      } catch (PropertyListFormatException | ParseException e) {
        // If we failed to parse, the plist may implicitly be a map. To handle this, wrap the plist
        // with {}.
        // TODO(bazel-team): Do this in a cleaner way.
        ByteSource concatenated = ByteSource.concat(
            ByteSource.wrap(new byte[] {'{'}),
            rawBytes,
            ByteSource.wrap(new byte[] {'}'}));
        try (InputStream in = concatenated.openStream()) {
          return (NSDictionary) PropertyListParser.parse(in);
        }
      }
    } catch (PropertyListFormatException | ParseException | ParserConfigurationException
        | SAXException e) {
      throw new IOException(e);
    }
  }

  /**
   * Writes the results of a merge operation to a plist file.
   * @param plistPath the path of the plist to write in binary format
   */
  public void writePlist(Path plistPath) throws IOException {
    try (OutputStream out = Files.newOutputStream(plistPath)) {
      BinaryPropertyListWriter.write(out, merged);
    }
  }

  /**
   * Writes a PkgInfo file based on certain keys in the merged plist.
   * @param pkgInfoPath the path of the PkgInfo file to write. In many iOS apps, this file just
   *     contains the raw string {@code APPL????}.
   */
  public void writePkgInfo(Path pkgInfoPath) throws IOException {
    String pkgInfo =
        Mapping.of(merged, "CFBundlePackageType").or(NSObject.wrap("APPL")).toString()
        + Mapping.of(merged, "CFBundleSignature").or(NSObject.wrap("????")).toString();
    Files.write(pkgInfoPath, pkgInfo.getBytes(StandardCharsets.UTF_8));
  }

  /** Invokes {@link #writePlist(Path)} and {@link #writePkgInfo(Path)}. */
  public void write(Path plistPath, Path pkgInfoPath) throws IOException {
    writePlist(plistPath);
    writePkgInfo(pkgInfoPath);
  }

  /**
   * Returns a map containing entries that should be added to the merged plist. These are usually
   * generated by Xcode automatically during the build process.
   */
  public static Map<String, NSObject> automaticEntries(
      Iterable<TargetDeviceFamily> targetedDeviceFamily, Platform platform, String sdkVersion,
      String minimumOsVersion) {
    ImmutableMap.Builder<String, NSObject> result = new ImmutableMap.Builder<>();
    List<Integer> uiDeviceFamily =
        Mapping.of(
            TargetDeviceFamily.UI_DEVICE_FAMILY_VALUES,
            ImmutableSet.copyOf(targetedDeviceFamily))
        .get();
    result.put("UIDeviceFamily", NSObject.wrap(uiDeviceFamily.toArray()));
    result.put("DTPlatformName", NSObject.wrap(platform.getLowerCaseNameInPlist()));
    result.put("DTSDKName", NSObject.wrap(platform.getLowerCaseNameInPlist() + sdkVersion));
    result.put("CFBundleSupportedPlatforms", new NSArray(NSObject.wrap(platform.getNameInPlist())));

    if (platform == Platform.DEVICE) {
      // TODO(bazel-team): Figure out if there are more appropriate values to put here, or if any
      // can be omitted. These have been copied from a plist file generated by Xcode for a device
      // build.
      result.put("DTCompiler", NSObject.wrap("com.apple.compilers.llvm.clang.1_0"));
      result.put("BuildMachineOSBuild", NSObject.wrap("13D65"));
      result.put("DTPlatformBuild", NSObject.wrap("11B508"));
      result.put("DTSDKBuild", NSObject.wrap("11B508"));
      result.put("DTXcode", NSObject.wrap("0502"));
      result.put("DTXcodeBuild", NSObject.wrap("5A3005"));
      result.put("DTPlatformVersion", NSObject.wrap(sdkVersion));
      result.put("MinimumOSVersion", NSObject.wrap(minimumOsVersion));
    }
    return result.build();
  }

  /**
   * Generates final merged Plist file and PkgInfo file in the specified locations, and includes the
   * "automatic" entries in the Plist.
   */
  public static PlistMerging from(List<Path> sourceFiles, Map<String, NSObject> automaticEntries,
      Map<String, String> substitutions, KeysToRemoveIfEmptyString keysToRemoveIfEmptyString)
          throws IOException {
    NSDictionary merged = PlistMerging.merge(sourceFiles);

    Set<String> conflictingEntries = Intersection.of(automaticEntries.keySet(), merged.keySet());
    Preconditions.checkArgument(conflictingEntries.isEmpty(),
        "The following plist entries are generated automatically, but are present in one of the "
        + "input lists: %s", conflictingEntries);
    merged.putAll(automaticEntries);

    for (Map.Entry<String, NSObject> entry : merged.entrySet()) {
      if (entry.getValue().toJavaObject() instanceof String) {
        String newValue = substituteEnvironmentVariable(
            substitutions, (String) entry.getValue().toJavaObject());
        merged.put(entry.getKey(), newValue);
      }
    }

    for (String key : keysToRemoveIfEmptyString) {
      if (Equaling.of(Mapping.of(merged, key), Optional.<NSObject>of(new NSString("")))) {
        merged.remove(key);
      }
    }

    return new PlistMerging(merged);
  }

  // Assume that if an RFC 1034 format string is specified, the value is RFC 1034 compliant.
  private static String substituteEnvironmentVariable(
      Map<String, String> substitutions, String string) {
    // The substitution is *not* performed recursively.
    for (Map.Entry<String, String> variable : substitutions.entrySet()) {
      for (String variableNameWithFormatString : withFormatStrings(variable.getKey())) {
        string = string
            .replace("${" + variableNameWithFormatString + "}", variable.getValue())
            .replace("$(" + variableNameWithFormatString + ")", variable.getValue());
      }
    }

    return string;
  }

  private static ImmutableSet<String> withFormatStrings(String variableName) {
    return ImmutableSet.of(variableName, variableName + ":rfc1034identifier");
  }

  @VisibleForTesting
  NSDictionary asDictionary() {
    return merged;
  }

  /**
   * Sets the given executable name on this merged plist in the {@code CFBundleExecutable}
   * attribute.
   *
   * @param executableName name of the bundle executable
   * @return this plist merging
   * @throws ValidationException if the plist already contains an incompatible
   *    {@code CFBundleExecutable} entry
   */
  public PlistMerging setExecutableName(String executableName) {
    NSString bundleExecutable = (NSString) merged.get("CFBundleExecutable");

    if (bundleExecutable == null) {
      merged.put("CFBundleExecutable", executableName);
    } else if (!executableName.equals(bundleExecutable.getContent())) {
      throw new ValidationException(String.format(
          "Blaze generated the executable %s but the Plist CFBundleExecutable is %s",
          executableName, bundleExecutable));
    }

    return this;
  }

  private static class Utf8BomSkippingByteSource extends ByteSource {

    private static final byte[] UTF8_BOM =
        new byte[] { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF };

    private final Path path;

    public Utf8BomSkippingByteSource(Path path) {
      this.path = path;
    }

    @Override
    public InputStream openStream() throws IOException {
      InputStream stream = new BufferedInputStream(Files.newInputStream(path));
      stream.mark(UTF8_BOM.length);
      byte[] buffer = new byte[UTF8_BOM.length];
      int read = stream.read(buffer);
      stream.reset();
      buffer = Arrays.copyOf(buffer, read);

      if (UTF8_BOM.length == read && Arrays.equals(buffer, UTF8_BOM)) {
        stream.skip(UTF8_BOM.length);
      }

      return stream;
    }
  }
}
