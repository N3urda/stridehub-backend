<?php

declare(strict_types=1);

namespace App\Tests\Application;

use App\Application\AndroidAppRelease;
use App\Infrastructure\ValueObject\String\KernelProjectDir;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;
use Symfony\Component\Filesystem\Filesystem;

final class AndroidAppReleaseTest extends TestCase
{
    private string $projectDir;
    private string $downloadsDir;
    private AndroidAppRelease $release;

    #[\Override]
    protected function setUp(): void
    {
        parent::setUp();
        $this->projectDir = sys_get_temp_dir().'/stridehub-android-release-test-'.bin2hex(random_bytes(8));
        $this->downloadsDir = $this->projectDir.'/public/downloads';
        new Filesystem()->mkdir($this->downloadsDir);
        $this->release = new AndroidAppRelease(KernelProjectDir::fromString($this->projectDir));
    }

    #[\Override]
    protected function tearDown(): void
    {
        new Filesystem()->remove($this->projectDir);
        parent::tearDown();
    }

    public function testItDoesNotAdvertiseAReleaseWithoutAManifest(): void
    {
        self::assertNull($this->release->current());
    }

    #[DataProvider('invalidJsonManifests')]
    public function testItDoesNotAdvertiseMalformedOrIncompleteManifests(string $contents): void
    {
        file_put_contents($this->downloadsDir.'/android-release.json', $contents);

        self::assertNull($this->release->current());
    }

    /** @return iterable<string, array{string}> */
    public static function invalidJsonManifests(): iterable
    {
        yield 'truncated JSON' => ['{"version":'];
        yield 'null' => ['null'];
        yield 'scalar' => ['"0.2.1"'];
        yield 'empty object' => ['{}'];
        yield 'array' => ['[]'];
        yield 'only a version' => ['{"version":"0.2.1"}'];
    }

    public function testItReturnsVerifiedMetadataAndADownloadPath(): void
    {
        $manifest = $this->validManifest();
        $this->writeManifest($manifest);

        $release = $this->release->current();
        self::assertNotNull($release);
        self::assertCount(7, $release);
        foreach ($manifest + ['downloadPath' => '/downloads/StrideHub-0.2.1.apk'] as $field => $value) {
            self::assertSame($value, $release[$field], $field);
        }
    }

    public function testItDoesNotTrustADownloadUrlSuppliedByTheManifest(): void
    {
        $manifest = $this->validManifest();
        $this->writeManifest($manifest + ['downloadPath' => 'https://example.invalid/unverified.apk']);

        $release = $this->release->current();
        self::assertNotNull($release);
        self::assertSame('/downloads/StrideHub-0.2.1.apk', $release['downloadPath']);
    }

    public function testItDoesNotAdvertiseAMissingApk(): void
    {
        $manifest = $this->validManifest();
        unlink($this->downloadsDir.'/'.$manifest['filename']);
        $this->writeManifest($manifest);

        self::assertNull($this->release->current());
    }

    public function testItDoesNotAdvertiseAnApkWhoseContentChanged(): void
    {
        $manifest = $this->validManifest();
        $this->writeManifest($manifest);
        // Keep the length identical so that checking file size alone cannot pass.
        file_put_contents($this->downloadsDir.'/'.$manifest['filename'], str_repeat('x', $manifest['sizeBytes']));

        self::assertNull($this->release->current());
    }

    public function testItDoesNotAdvertiseAnApkWithTheWrongSize(): void
    {
        $manifest = $this->validManifest();
        ++$manifest['sizeBytes'];
        $this->writeManifest($manifest);

        self::assertNull($this->release->current());
    }

    #[DataProvider('requiredFields')]
    public function testItRequiresCompleteReleaseMetadata(string $field): void
    {
        $manifest = $this->validManifest();
        unset($manifest[$field]);
        $this->writeManifest($manifest);

        self::assertNull($this->release->current());
    }

    /** @return iterable<string, array{string}> */
    public static function requiredFields(): iterable
    {
        foreach (['version', 'versionCode', 'minAndroid', 'filename', 'sha256', 'sizeBytes'] as $field) {
            yield $field => [$field];
        }
    }

    #[DataProvider('invalidMetadata')]
    public function testItRejectsInvalidReleaseMetadata(string $field, mixed $value): void
    {
        $manifest = $this->validManifest();
        $manifest[$field] = $value;
        $this->writeManifest($manifest);

        self::assertNull($this->release->current());
    }

    /** @return iterable<string, array{string, mixed}> */
    public static function invalidMetadata(): iterable
    {
        yield 'empty version' => ['version', ''];
        yield 'non-string version' => ['version', 2];
        yield 'zero version code' => ['versionCode', 0];
        yield 'negative version code' => ['versionCode', -1];
        yield 'non-integer version code' => ['versionCode', '6'];
        yield 'empty Android requirement' => ['minAndroid', ''];
        yield 'invalid checksum' => ['sha256', 'not-a-sha256'];
        yield 'zero byte size' => ['sizeBytes', 0];
    }

    #[DataProvider('unsafeFilenames')]
    public function testItRejectsFilenamesOutsideTheDownloadDirectory(string $filename): void
    {
        $manifest = $this->validManifest();
        $manifest['filename'] = $filename;
        $this->writeManifest($manifest);

        self::assertNull($this->release->current());
    }

    /** @return iterable<string, array{string}> */
    public static function unsafeFilenames(): iterable
    {
        yield 'parent traversal' => ['../StrideHub-0.2.1.apk'];
        yield 'nested path' => ['nested/StrideHub-0.2.1.apk'];
        yield 'absolute path' => ['/tmp/StrideHub-0.2.1.apk'];
        yield 'Windows traversal' => ['..\\StrideHub-0.2.1.apk'];
        yield 'URL' => ['https://example.invalid/StrideHub-0.2.1.apk'];
        yield 'null byte' => ["StrideHub-0.2.1.apk\0.apk"];
    }

    public function testItRejectsTraversalEvenWhenTheOutsideFileHasAMatchingChecksum(): void
    {
        $manifest = $this->validManifest();
        rename($this->downloadsDir.'/'.$manifest['filename'], $this->projectDir.'/public/'.$manifest['filename']);
        $manifest['filename'] = '../'.$manifest['filename'];
        $this->writeManifest($manifest);

        self::assertNull($this->release->current());
    }

    public function testItRejectsAnApkSymlinkOutsideTheDownloadDirectory(): void
    {
        $manifest = $this->validManifest();
        $apk = $this->downloadsDir.'/'.$manifest['filename'];
        $outside = $this->projectDir.'/'.$manifest['filename'];
        rename($apk, $outside);
        symlink($outside, $apk);
        $this->writeManifest($manifest);

        self::assertNull($this->release->current());
    }

    /** @return array{version: string, versionCode: int, minAndroid: string, filename: string, sha256: string, sizeBytes: int} */
    private function validManifest(): array
    {
        $contents = 'DEMO Android package contents for release metadata verification only';
        $filename = 'StrideHub-0.2.1.apk';
        file_put_contents($this->downloadsDir.'/'.$filename, $contents);

        return [
            'version' => '0.2.1',
            'versionCode' => 6,
            'minAndroid' => '8.0',
            'filename' => $filename,
            'sha256' => hash('sha256', $contents),
            'sizeBytes' => strlen($contents),
        ];
    }

    /** @param array<string, mixed> $manifest */
    private function writeManifest(array $manifest): void
    {
        file_put_contents($this->downloadsDir.'/android-release.json', json_encode($manifest, JSON_THROW_ON_ERROR));
    }
}
