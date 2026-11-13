# nativephp-audiobook

A NativePHP Mobile plugin (Android) that turns a book voiced by a TTS server into an audiobook on the phone:

- **fetches** the voiced pages in the background (the page being heard first) while the server is still voicing the rest;
- **plays** page after page in a foreground service, with the **screen off** and the app closed;
- media **notification** and **lock-screen** controls (previous page, play/pause, next page, stop), headphone buttons, pauses for calls and
  when headphones are unplugged; speed 0.5x to 2.5x;
- a page not on the phone yet is played straight from the server; a page the server has not voiced yet is waited for;
- keeps the place (book, page, ms) and a list of the pages heard, for the app to count listening.

The server is the Hodie TTS API (`POST /api/books`, `GET /api/books/{id}`, `GET /api/books/{id}/pages/{n}/audio`, key in `X-TTS-Key`).

```php
use Blutrixx\AudioBook\Facades\AudioBook;

AudioBook::prepare('b42', $remoteId, 'https://tts.example.com/api', $key, 'Things Fall Apart', [1, 2, 3, 5, 6], total: 6);
AudioBook::play('b42', page: 3, speed: 1.15);
AudioBook::control('pause');
AudioBook::state('b42', drain: true);   // ['player' => [...], 'book' => [...], 'heard' => [['n' => 3, 'ms' => 92000, 'done' => true, ...]]]
AudioBook::delete('b42');
```

Audio files live in the app's own folder (`<external files>/audiobooks/<book>/<page>.mp3`); no storage permission is needed.
