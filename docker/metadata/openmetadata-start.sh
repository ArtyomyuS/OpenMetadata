#!/bin/bash
#  Copyright 2021 Collate
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#  http://www.apache.org/licenses/LICENSE-2.0
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.

MYSQL="${MYSQL_HOST:-mysql}":"${MYSQL_PORT:-3306}"
while ! wget -O /dev/null -o /dev/null "${MYSQL}";
  do echo "Trying to connect to ${MYSQL}"; sleep 5;
done
cd /openmetadata-*/
./bootstrap/bootstrap_storage.sh migrate-all
./bin/openmetadata-server-start.sh conf/openmetadata.yaml
