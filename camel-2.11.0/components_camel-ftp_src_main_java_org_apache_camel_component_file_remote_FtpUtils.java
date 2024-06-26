/**
* Licensed to the Apache Software Foundation (ASF) under one or more
* contributor license agreements.  See the NOTICE file distributed with
* this work for additional information regarding copyright ownership.
* The ASF licenses this file to You under the Apache License, Version 2.0
* (the "License"); you may not use this file except in compliance with
* the License.  You may obtain a copy of the License at
*
*      http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package org.apache.camel.component.file.remote;

import java.io.File;
import java.util.Iterator;
import java.util.Stack;

import org.apache.camel.util.FileUtil;

/**
* Various FTP utils.
*/
public final class FtpUtils {

private FtpUtils() {
}

/**
* Compacts a path by stacking it and reducing <tt>..</tt>,
* and uses OS specific file separators (eg {@link java.io.File#separator}).
* <p/>
* <b>Important: </b> This implementation works for the camel-ftp component
* for various FTP clients and FTP servers using different platforms and whatnot.
* This implementation has been working for many Camel releases, and is included here
* to restore patch compatibility with the Camel releases.
*/
public static String compactPath(String path) {
if (path == null) {
return null;
}

// only normalize if contains a path separator
if (path.indexOf(File.separator) == -1) {
return path;
}

// preserve ending slash if given in input path
boolean endsWithSlash = path.endsWith("/") || path.endsWith("\\");

// preserve starting slash if given in input path
boolean startsWithSlash = path.startsWith("/") || path.startsWith("\\");

Stack<String> stack = new Stack<String>();

String separatorRegex = File.separator;
if (FileUtil.isWindows()) {
separatorRegex = "\\\\";
}
String[] parts = path.split(separatorRegex);
for (String part : parts) {
if (part.equals("..") && !stack.isEmpty() && !"..".equals(stack.peek())) {
// only pop if there is a previous path, which is not a ".." path either
stack.pop();
} else if (part.equals(".") || part.isEmpty()) {
// do nothing because we don't want a path like foo/./bar or foo//bar
} else {
stack.push(part);
}
}

// build path based on stack
StringBuilder sb = new StringBuilder();

if (startsWithSlash) {
sb.append(File.separator);
}

for (Iterator<String> it = stack.iterator(); it.hasNext();) {
sb.append(it.next());
if (it.hasNext()) {
sb.append(File.separator);
}
}

if (endsWithSlash) {
sb.append(File.separator);
}

// there has been problems with double slashes,
// so avoid this by removing any 2nd slash
if (sb.length() >= 2) {
boolean firstSlash = sb.charAt(0) == '/' || sb.charAt(0) == '\\';
boolean secondSlash = sb.charAt(1) == '/' || sb.charAt(1) == '\\';
if (firstSlash && secondSlash) {
// remove 2nd clash
sb = sb.replace(1, 2, "");
}
}

return sb.toString();
}

}