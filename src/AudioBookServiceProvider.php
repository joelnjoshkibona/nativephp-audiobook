<?php

namespace Blutrixx\AudioBook;

use Illuminate\Support\ServiceProvider;

class AudioBookServiceProvider extends ServiceProvider
{
    public function register(): void
    {
        $this->app->singleton(AudioBook::class, fn () => new AudioBook());
        $this->app->alias(AudioBook::class, 'nativephp.audiobook');
    }
}
