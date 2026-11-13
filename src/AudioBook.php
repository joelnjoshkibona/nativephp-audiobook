<?php

namespace Blutrixx\AudioBook;

/**
 * Audiobooks from a voice server (the Hodie TTS server: POST /api/books voices a whole book in the background).
 *
 * Prepare a book once with where its audio is; the phone then fetches the voiced pages by itself, the page being heard first, and plays them
 * page after page in a foreground service: the screen may be off and the app closed. The player's place and the pages heard are kept on
 * the phone; read them with state(), and take the heard list with $drain = true to count the listening once.
 *
 * Every method returns an array. A failed call returns ['status' => 'error', 'code' => ..., 'message' => ...].
 */
class AudioBook
{
    /**
     * @param  string  $book  Your own id for the book.
     * @param  string  $remote  The server's id for the voiced book.
     * @param  string  $base  The server's API address, for example 'https://tts.example.com/api'.
     * @param  array<int,int>  $pages  The page numbers that have sound, in order (pages without text are left out).
     * @param  int  $total  The book's page count, for "page 12 of 300".
     */
    public function prepare(string $book, string $remote, string $base, string $key, string $title, array $pages, int $total = 0, bool $sync = true): array
    {
        return $this->call('AudioBook.Prepare', ['book' => $book, 'remote' => $remote, 'base' => $base, 'key' => $key, 'title' => $title,
            'pages' => array_values(array_map('intval', $pages)), 'total' => $total, 'sync' => $sync]);
    }

    /** Play from a page (and a place in it, in ms) at a speed (0.5 to 2.5). */
    public function play(string $book, int $page = 0, int $pos = 0, ?float $speed = null): array
    {
        return $this->call('AudioBook.Play', array_filter(['book' => $book, 'page' => $page, 'pos' => $pos, 'speed' => $speed], fn ($v) => $v !== null));
    }

    /** pause, resume, toggle, next, prev, stop; speed with $speed; seek with $page and $pos. */
    public function control(string $action, ?float $speed = null, ?int $page = null, ?int $pos = null): array
    {
        return $this->call('AudioBook.Control', array_filter(['action' => $action, 'speed' => $speed, 'page' => $page, 'pos' => $pos], fn ($v) => $v !== null));
    }

    /**
     * ['player' => [...], 'book' => [...] when $book is given, 'heard' => [['book', 'n', 'ms', 'done', 'at'], ...]].
     * With $drain the heard list is cleared on the phone: count it then.
     */
    public function state(?string $book = null, bool $drain = false): array
    {
        $r = $this->call('AudioBook.State', array_filter(['book' => $book, 'drain' => $drain ?: null], fn ($v) => $v !== null));
        if (($r['status'] ?? null) === 'error') {
            return $r;
        }
        $d = $r['data'] ?? $r;
        foreach (['player', 'book', 'heard'] as $k) {
            if (isset($d[$k]) && is_string($d[$k])) {
                $d[$k] = json_decode($d[$k], true);
            }
        }

        return $d;
    }

    /** Remove a book's audio from the phone. */
    public function delete(string $book): array
    {
        return $this->call('AudioBook.Delete', ['book' => $book]);
    }

    private function call(string $function, array $params = []): array
    {
        if (! function_exists('nativephp_call')) {
            return ['status' => 'error', 'code' => 'NOT_ON_DEVICE', 'message' => 'Audiobooks only play inside the NativePHP app.'];
        }

        $result = nativephp_call($function, json_encode($params === [] ? new \stdClass() : $params));

        return $result ? (json_decode($result, true) ?? []) : [];
    }
}
