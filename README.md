# Parking Finder (Android)

Шукає паркінги поруч із вашою поточною локацією на карті OpenStreetMap і показує ціну за годину та за добу.
Найдешевші паркінги позначені зеленим, середні жовтим, найдорожчі червоним.

Ключі API не потрібні.

## Збірка
- Через GitHub: Actions → Build APK → Run workflow, готовий `app-debug.apk` буде в Artifacts.
- Через Android Studio: відкрийте папку, дочекайтесь Gradle Sync, Build → Build APK(s).

## Звідки ціни
- Паркінги беруться з OpenStreetMap через Overpass API.
- Якщо в OSM є тег `fee=no`, паркінг безкоштовний ($0). Якщо є тег `charge` на кшталт `2 USD/hour`, ціна береться з нього.
- В усіх інших випадках показується демонстраційна ціна з позначкою `~` (не реальна).
- Для реальних цін реалізуйте інтерфейс `PriceProvider` (`data/ParkingSpot.kt`) через ParkWhiz, SpotHero або інше API.

## Структура
- `MainActivity.kt`: карта (osmdroid), список, дозвіл на локацію
- `MainViewModel.kt`: отримання локації та пошук
- `PriceBadge.kt`: кольорові бейджі з цінами
- `data/OverpassRepository.kt`: запит до Overpass API
- `data/ParkingSpot.kt`: модель і провайдер цін

Карта: © OpenStreetMap contributors.
