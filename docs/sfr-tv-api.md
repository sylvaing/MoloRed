# SFR TV / RED TV web API (as used by tv.sfr.fr, web player "gen8" v2.11.2)

Reverse-engineered on 2026-11-15 from the public web player config
(`https://tv.sfr.fr/assets/configs/config.json`) and its JS bundles, with a
logged-in RED by SFR account. No secret is stored here.

## 1. Authentication (OIDC implicit flow on SFR CAS)

```
GET https://www.sfr.fr/cas/oidc/authorize
    ?client_id=taLOzliUuwLurpOGJBxzzVuvzdeARJ     # public web client id
    &scope=openid
    &response_type=token
    &redirect_uri=https://tv.sfr.fr/
    &state=<random>
```

After login, CAS redirects to `https://tv.sfr.fr/?state=…&access_token=<JWT>`.
The `access_token` is a ~2.9 kB JWT: **the TV backend token ("castoken") is its
`tu` claim** (~193 chars) and `exp` its expiry — the web player does exactly
this in `handleAccessToken`. Sending the whole JWT gives `SP_INVALIDE_TOKEN` (403).
The castoken lives ~13 min; the web app re-runs the
authorize URL every 5 min (`OIDC_REFRESH_TIME`), which succeeds silently as long
as the CAS session cookies are valid. Our app does the same with a hidden WebView.

## 2. Channel list

```
GET https://ws-backendtv.sfr.fr/sekai-service-plan/public/v2/service-list?app=gen8&device=browser&token=<castoken>
```

Array of ~450 services. Useful fields: `serviceId` (e.g. `NEUF_FRANCE2`),
`zappingId` (channel number), `name`, `epgId`, `access` (true if in the
subscription — 142 for a RED fibre account), `isLimited`, `images[type=color]`,
`streams[] {drm: WIDEVINE|PLAYREADY|FAIRPLAY, format: dash|hls, url}`.

## 3. Signed stream URL ("mkpc")

```
POST https://ws-backendtv.sfr.fr/sekai-service-plan/public/v1/mkpc/jwt?token=<castoken>
{"url": "<stream url>", "issuer": "sfrtv_web", "eme": <1..5>}
→ {"url": "<stream url>&jwt=<jwt>"}
```

`eme` = Widevine robustness level the client supports
(1 SW_SECURE_CRYPTO, 2 SW_SECURE_DECODE, 3 HW_SECURE_CRYPTO, 4 HW_SECURE_DECODE,
5 HW_SECURE_ALL). The web player falls back to the unsigned URL if this fails.

## 4. Widevine licence

```
POST https://ws-backendtv.sfr.fr/asgard-drm-widevine/public/licence
Header customdata: description=<UA>&deviceId=byPassARTHIUS&deviceName=<name>
  &deviceType=PC&screenType=PC&osName=<os>&osVersion=<ver>&persistent=false
  &resolution=<WxH>&tokenType=castoken&enabledMkpc=true&tokenSSO=<castoken>
  &type=LIVEOTT&accountId=<contractId>
Body: Widevine challenge (binary)
```

For live TV, `type=LIVEOTT` (or `RESTART` / `NPVR`) and `accountId` are
mandatory; without them the server answers 400 `ASGWV0050 entitlementId is null`.
`accountId` = `siebelId` of the ACTIVATED entry of `internetSubscriptions[]` in
`GET heimdall-core/public/api/v3/userProfiles?token=<castoken>`.
Verified working on a Fire TV Stick 4K Max (AFTKM, Fire OS 8, Widevine L1) on 2026-11-15.

Values are joined with `&` without URL encoding (`toCustomParams`).
`deviceId=byPassARTHIUS` is the literal value the official web client sends.

## 5. Concurrent-stream control (only for `isLimited` channels)

`POST https://ws-backendtv.sfr.fr/asgard-session/public/v2/connect` then
`/verifyAccess` every `ttlChannel` with
`{app, device, token, idAsc, idChannel, idDevice, nameDevice}`; `idAsc` comes
from `heimdall-core/public/api/v3/userProfiles`. A 403 lists the devices already
watching. Not implemented: on 2026-11-15 none of the ~450 services of
`service-list` had `isLimited: true`.

## 5b. Time-shift / start-over

- Live manifest `…?start=LIVE&end=END…` is dynamic DASH with
  `timeShiftBufferDepth` ≈ 14 min (seekable in place).
- Start-over: `GET sekai-service-plan/public/api/streams/v2/restart?app=gen8&device=browser&token=…&startOverId=<startOverId>&startTimestamp=<s>`
  returns the same stream URLs with `start=<ISO-8601 UTC>` (in practice now − 8 h).
  Any `start=YYYY-MM-DDTHH:MM:SSZ` within that range works: the manifest then covers
  start → live edge. Sign it with mkpc like the live URL; licence `type=RESTART`.
- Channel flags: `timeShift` (139/142), `startOver` (108/142), `aff.timeShift` /
  `aff.startOver` = no fast-forward (9 channels).

## 6. EPG (public, no auth)

`https://static-cdn.tv.sfr.net/data/epg/gen8/guide_web_YYYYMMDD.json`
→ `{"epg": {"<epgId>": [{title, genre, startDate, endDate (ms), images[]…}]}}`
(~10 MB per day).

## Errors seen

- `S-6001` playback error in a browser without a Widevine CDM.
- `S-6007-MAX_DEVICES`, `S-6007-TOKEN_EXPIRED`, asgard `GSL004` (expired token).
