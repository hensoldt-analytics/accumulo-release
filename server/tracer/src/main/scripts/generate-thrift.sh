#! /usr/bin/env bash

# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# This script will regenerate the thrift code for accumulo-trace.
INCLUDED_MODULES=(../../core)
BASE_OUTPUT_PACKAGE='org.apache.accumulo'
PACKAGES_TO_GENERATE=(tracer)

. ../../core/src/main/scripts/generate-thrift.sh

# Ensure serialVersionUID stays the same for all 1.6.x versions (see ACCUMULO-3433, ACCUMULO-3132)
sed -i -e 's/\(public class TInfo .*\)$/\1\
\
  private static final long serialVersionUID = -4659975753252858243l; \/\/ See ACCUMULO-3132\
/' src/main/java/org/apache/accumulo/trace/thrift/TInfo.java
