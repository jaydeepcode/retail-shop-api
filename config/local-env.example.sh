# Secrets for local development. Copy to config/local-env.sh, fill in, and source it
# before running the app:  set -a; . config/local-env.sh; set +a
# config/local-env.sh is git-ignored. Never commit real values.
#
# Every placeholder below is deliberately without a default: if a value is missing the
# app fails to start, rather than booting with an empty signing key.

# BASE64 of at least 32 random bytes (256 bits). jjwt 0.9.1's
# signWith(SignatureAlgorithm, String) base64-decodes this value and does NOT reject a
# short key, so nothing checks the length for you. Generate with:
#     openssl rand -base64 32
export JWT_SECRET=

# Local MySQL. DB_USERNAME defaults to CISADM if unset.
export DB_USERNAME=
export DB_PASSWORD=

# Pump controller credential, as the full Authorization header value ("Basic <base64>").
# Must match what is configured ON THE DEVICE.
export WATER_ESP_API_KEY=
