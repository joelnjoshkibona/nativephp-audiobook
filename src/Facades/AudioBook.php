<?php

namespace Blutrixx\AudioBook\Facades;

use Illuminate\Support\Facades\Facade;

/**
 * @method static array prepare(string $book, string $remote, string $base, string $key, string $title, array $pages, int $total = 0, bool $sync = true)
 * @method static array play(string $book, int $page = 0, int $pos = 0, ?float $speed = null)
 * @method static array control(string $action, ?float $speed = null, ?int $page = null, ?int $pos = null)
 * @method static array state(?string $book = null, bool $drain = false)
 * @method static array delete(string $book)
 *
 * @see \Blutrixx\AudioBook\AudioBook
 */
class AudioBook extends Facade
{
    protected static function getFacadeAccessor(): string
    {
        return \Blutrixx\AudioBook\AudioBook::class;
    }
}
