# Parking Finder (Android)

Шукає паркінги поруч із вашою поточною локацією на Google Maps і показує ціну за годину та за добу.

## Запуск
1. Відкрийте папку в Android Studio (Koala або новіша) і дочекайтесь Gradle Sync.
2. У Google Cloud Console увімкніть **Maps SDK for Android** та **Places API (New)**, створіть API-ключ.
3. У файлі `local.properties` у корені проєкту додайте рядок:
   `MAPS_API_KEY=ваш_ключ`
4. Запустіть на телефоні або емуляторі (емулятор: задайте локацію в США через Extended controls → Location).

## Важливо про ціни
Google Places не віддає ціни паркінгу. Зараз використовується `DemoPriceProvider` (випадкові, але стабільні демо-ціни).
Для реальних цін реалізуйте інтерфейс `PriceProvider` (файл `data/ParkingSpot.kt`) через ParkWhiz, SpotHero або інше API
і передайте його в `PlacesRepository` у `MainViewModel`.

## Структура
- `MainActivity.kt` — карта, список, дозвіл на локацію
- `MainViewModel.kt` — отримання локації та пошук
- `data/PlacesRepository.kt` — Places API (New), Nearby Search, тип `parking`
- `data/ParkingSpot.kt` — модель і провайдер цін
