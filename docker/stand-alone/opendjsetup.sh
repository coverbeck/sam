#!/usr/bin/env sh
# Default setup script
#
# Copyright (c) 2016-2017 ForgeRock AS. Use of this source code is subject to the
# Common Development and Distribution License (CDDL) that can be found in the LICENSE file

echo "Setting up default OpenDJ instance."

INIT_OPTION="--addBaseEntry"

if [ -n "${NUMBER_SAMPLE_USERS+set}" ]; then
    INIT_OPTION="--sampleData ${NUMBER_SAMPLE_USERS}"
fi

/opt/opendj/setup directory-server --ldapPort 390 \
  --instancePath /opt/opendj/data \
  --adminConnectorPort 4444 \
  --baseDN dc=example,dc=com -h opendj --rootUserPassword "testtesttest" \
  --acceptLicense \
  ${INIT_OPTION}

# If any optional LDIF files are present, load them.

if [ -d /opt/opendj/bootstrap/ldif ]; then
   echo "Found optional schema files in bootstrap/ldif. Will load them"
  for file in /opt/opendj/bootstrap/ldif/*;  do
      echo "Loading $file"
       sed -e "s/@BASE_DN@/$BASE_DN/" <${file}  >/tmp/file.ldif
      /opt/opendj/bin/ldapmodify -D "cn=Directory Manager" -h localhost -p 390 -w ${ROOTPASS} -f /tmp/file.ldif
  done
fi

/opt/opendj/bin/dsconfig \
 set-http-endpoint-prop \
 --hostname localhost  \
 --port 4444 \
 --bindDN "cn=Directory Manager" \
 --bindPassword ${ROOTPASS} \
 --endpoint-name /api \
 --set authorization-mechanism:"HTTP Basic" \
 --set config-directory:config/rest2ldap/endpoints/api \
 --set enabled:true \
 --no-prompt \
 --trustAll
