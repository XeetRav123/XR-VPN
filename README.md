# XR VPN — split tunnel skeleton

## UI
`app/src/main/assets/index.html` — оболочка с:
- Services / Locations / Settings
- Theme + custom color sliders
- Routing: All traffic | Bypass list | Only list

## Native
- `MainActivity` — WebView + `XRVpn.connect(json)` / `disconnect()`
- `XrVpnService` — `VpnService` + разбор `routing.mode` / `routing.list`

## Modes
| mode | поведение |
|------|-----------|
| full | `0.0.0.0/0` в туннель |
| only | в туннель только IP/CIDR/резолв доменов из списка |
| bypass | полный туннель; список для движка «исключить» (WG AllowedIPs / userspace) |

## Важно
Поднятие TUN ≠ рабочий шифрованный VPN. Нужно подключить WireGuard/OpenVPN и кормить ему fd/конфиг.
`addDisallowedApplication(packageName)` — трафик самого приложения XR не уходит в VPN (анти-петля).

## Сборка
Скопируй java-файлы в свой модуль, merge manifest snippet, положи HTML в assets, minSdk 24+.
