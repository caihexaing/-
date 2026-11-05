# Bundled DNS Rules

`app/src/main/assets/ad-domains.txt` contains 987 blocked domains and 196 exact-host exceptions. It is generated from the existing curated seed list and the domain-only subset of AdGuard's mobile filter.

Source snapshot: `vendor/adguard-mobile-filter.txt`, downloaded 2026-11-05 from `https://raw.githubusercontent.com/AdguardTeam/FiltersRegistry/master/filters/filter_11_Mobile/filter.txt`.

SHA-256 of the upstream snapshot: `ad77b2ebacc7599f9bed809d31c93715a7de6abb2d1726e7ed0b1231a8aa45af`.

The upstream filter identifies its license as the GNU General Public License, version 3. The license text is included in the source tree at `vendor/AdGuardFilters-LICENSE` and in the APK as `ad-rules-license.txt`. Preserve that license and this attribution when redistributing the derived rules. The list is data and is not included as executable code.

To regenerate the asset from the preserved snapshot:

```powershell
python tools/rules/compile_mobile_filter.py --input tools/rules/vendor/adguard-mobile-filter.txt --baseline tools/rules/quietguard-seed-domains.txt --output work/ad-domains-next.txt
```

The baseline is the original curated seed list, kept separate so upstream removals are not silently retained. Review source changes and the generated diff before replacing the APK asset.

Only exact `||hostname^` block rules are imported. Path, wildcard, site-scoped, app-scoped, and other modified block rules are skipped because the DNS engine cannot represent them. Host exceptions with modifiers or paths become exact-host exceptions (`~host`); unmodified host exceptions become suffix exceptions (`!host`). Generic cosmetic-only exceptions and non-host exceptions are omitted. App-scoped host exceptions are applied globally to that exact host because the Android VPN tunnel does not expose which selected application originated an individual DNS packet. This conservative mapping can reduce blocking on that host for other selected apps; it avoids broadening the exception to the host's subdomains.

This is a fixed APK snapshot and does not enable online rule updates. Optional SNI filtering is documented separately; neither DNS nor SNI filtering decrypts HTTPS or protects against all direct-IP, DoH, cached, or same-host ads.
