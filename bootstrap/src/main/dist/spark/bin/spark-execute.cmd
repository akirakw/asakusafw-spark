@REM
@REM Copyright 2011-2017 Asakusa Framework Team.
@REM
@REM Licensed under the Apache License, Version 2.0 (the "License");
@REM you may not use this file except in compliance with the License.
@REM You may obtain a copy of the License at
@REM
@REM     http://www.apache.org/licenses/LICENSE-2.0
@REM
@REM Unless required by applicable law or agreed to in writing, software
@REM distributed under the License is distributed on an "AS IS" BASIS,
@REM WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
@REM See the License for the specific language governing permissions and
@REM limitations under the License.
@REM

@echo off
setlocal

if not defined ASAKUSA_HOME (
    echo environment variable %%ASAKUSA_HOME%% must be defined
    exit /b 1
)

REM set temporary Hadoop home to detect winutils.exe
if not defined HADOOP_HOME set HADOOP_HOME=%ASAKUSA_HOME%\hadoop

set java_jar=%ASAKUSA_HOME%\spark\lib\asakusa-spark-bootstrap.jar
java -jar %java_jar% %*
exit /b

:UNSUPPORTED
echo Asakusa on Spark does not support Windows platform 1>&2
exit /b 1
