<?php

/**
 * Plugin validation tests for AudioBook.
 *
 * Run with: ./vendor/bin/pest
 */

beforeEach(function () {
    $this->pluginPath = dirname(__DIR__);
    $this->manifest = json_decode(file_get_contents($this->pluginPath.'/nativephp.json'), true);
});

describe('Plugin Manifest', function () {
    it('is valid json with the required fields', function () {
        expect(json_last_error())->toBe(JSON_ERROR_NONE);
        expect($this->manifest)->toHaveKeys(['name', 'namespace', 'bridge_functions']);
        expect($this->manifest['name'])->toBe('blutrixx/nativephp-audiobook');
        expect($this->manifest['namespace'])->toBe('AudioBook');
    });

    it('names a Kotlin class for every bridge function', function () {
        $kotlin = file_get_contents($this->pluginPath.'/resources/android/AudioBookFunctions.kt');
        foreach ($this->manifest['bridge_functions'] as $function) {
            expect($function['name'])->toStartWith('AudioBook.');
            $class = substr($function['android'], strrpos($function['android'], '.') + 1);
            expect($kotlin)->toContain("class {$class}(");
        }
    });

    it('calls only bridge functions that the manifest declares', function () {
        $php = file_get_contents($this->pluginPath.'/src/AudioBook.php');
        preg_match_all("/'(AudioBook\.[A-Za-z]+)'/", $php, $m);
        $declared = array_column($this->manifest['bridge_functions'], 'name');
        expect($m[1])->not->toBeEmpty();
        foreach (array_unique($m[1]) as $name) {
            expect($declared)->toContain($name);
        }
    });

    it('declares the player service with both foreground types and their permissions', function () {
        $svc = collect($this->manifest['android']['services'])->firstWhere('name', '.AudioBookService');
        expect($svc['foregroundServiceType'])->toBe('mediaPlayback|dataSync');
        expect(file_exists($this->pluginPath.'/resources/android/AudioBookService.kt'))->toBeTrue();
        expect($this->manifest['android']['permissions'])->toContain('android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK', 'android.permission.FOREGROUND_SERVICE_DATA_SYNC', 'android.permission.INTERNET');
    });

    it('handles every control action the PHP side documents', function () {
        $kotlin = file_get_contents($this->pluginPath.'/resources/android/AudioBookFunctions.kt');
        foreach (['pause', 'resume', 'toggle', 'next', 'prev', 'stop', 'speed', 'seek'] as $a) {
            expect($kotlin)->toContain('"'.$a.'" ->');
        }
    });
});

describe('Kotlin sources', function () {
    it('all declare the plugin package', function () {
        foreach (glob($this->pluginPath.'/resources/android/*.kt') as $file) {
            expect(file_get_contents($file))->toContain('package com.blutrixx.plugins.audiobook');
        }
    });

    it('never write the book text or title to the app log', function () {
        $svc = file_get_contents($this->pluginPath.'/resources/android/AudioBookService.kt');
        preg_match_all('/AppLogBridge\.w\([^\n]*/', $svc, $m);
        foreach ($m[0] as $line) {
            expect($line)->not->toContain('title')->not->toContain('text');
        }
    });
});

describe('PHP side', function () {
    it('answers off the phone instead of failing', function () {
        require_once $this->pluginPath.'/src/AudioBook.php';
        $r = (new Blutrixx\AudioBook\AudioBook())->play('b1');
        expect($r['code'])->toBe('NOT_ON_DEVICE');
    });
});
