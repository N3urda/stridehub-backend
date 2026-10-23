<?php

declare(strict_types=1);

namespace App\Tests\Infrastructure\Http;

use App\Application\AppVersion;
use App\Infrastructure\Config\DevMode;
use App\Infrastructure\Http\AssetVersioningStrategy;
use App\Infrastructure\ValueObject\Identifier\UuidFactory;
use App\Infrastructure\ValueObject\String\KernelProjectDir;
use App\Tests\Infrastructure\ValueObject\Identifier\FakeUuidFactory;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;
use Symfony\Component\Filesystem\Filesystem;

final class AssetVersioningStrategyTest extends TestCase
{
    private string $projectDir;

    #[\Override]
    protected function setUp(): void
    {
        parent::setUp();
        $this->projectDir = sys_get_temp_dir().'/stridehub-asset-version-test-'.bin2hex(random_bytes(8));
        new Filesystem()->mkdir($this->projectDir.'/public/css');
    }

    #[\Override]
    protected function tearDown(): void
    {
        new Filesystem()->remove($this->projectDir);
        parent::tearDown();
    }

    public function testApplyVersionKeepsTheLeadingSlashAndFallsBackForMissingFiles(): void
    {
        $strategy = $this->strategy();
        $version = AppVersion::getSemanticVersion();
        self::assertSame('/test/file?'.$version, $strategy->applyVersion('/test/file'));
        self::assertSame('test/file?'.$version, $strategy->applyVersion('test/file'));
        self::assertSame($version, $strategy->getVersion('css/missing.css'));
    }

    public function testEmptyPathKeepsTheSemanticVersionForThePageMetadata(): void
    {
        self::assertSame(AppVersion::getSemanticVersion(), $this->strategy()->getVersion(''));
    }

    public function testExistingStaticFilesUseTheirContentHash(): void
    {
        $contents = '.hub-app-link{display:inline-flex}';
        file_put_contents($this->projectDir.'/public/css/shell.css', $contents);
        $hash = hash('sha256', $contents);
        self::assertSame($hash, $this->strategy()->getVersion('css/shell.css'));
        self::assertSame($hash, $this->strategy()->getVersion('/css/shell.css'));
        self::assertSame('/css/shell.css?'.$hash, $this->strategy()->applyVersion('/css/shell.css'));
        self::assertSame('css/shell.css?'.$hash, $this->strategy()->applyVersion('css/shell.css'));
    }

    public function testTheVersionChangesWithTheBytesEvenWhenSizeAndModificationTimeAreUnchanged(): void
    {
        $file = $this->projectDir.'/public/css/shell.css';
        file_put_contents($file, 'body{color:red}');
        touch($file, 1700000000);
        $strategy = $this->strategy();
        $before = $strategy->getVersion('css/shell.css');
        file_put_contents($file, 'body{color:tan}');
        touch($file, 1700000000);
        self::assertNotSame($before, $strategy->getVersion('css/shell.css'));
        self::assertSame(hash('sha256', 'body{color:tan}'), $strategy->getVersion('css/shell.css'));
    }

    public function testTheVersionIsStableAcrossInstancesForUnchangedBytes(): void
    {
        file_put_contents($this->projectDir.'/public/css/shell.css', 'body{color:red}');
        self::assertSame($this->strategy()->getVersion('css/shell.css'), $this->strategy()->getVersion('css/shell.css'));
    }

    public function testDevelopmentModeStillRequestsAFreshRandomVersionEveryTime(): void
    {
        file_put_contents($this->projectDir.'/public/css/shell.css', 'body{color:red}');
        $uuidFactory = $this->createMock(UuidFactory::class);
        $uuidFactory->expects(self::exactly(3))->method('random')->willReturnOnConsecutiveCalls('first', 'second', 'third');
        $strategy = new AssetVersioningStrategy(DevMode::fromString('1'), $uuidFactory, KernelProjectDir::fromString($this->projectDir));
        self::assertSame('/css/shell.css?first', $strategy->applyVersion('/css/shell.css'));
        self::assertSame('/css/shell.css?second', $strategy->applyVersion('/css/shell.css'));
        self::assertSame('third', $strategy->getVersion(''));
    }

    #[DataProvider('unsafePaths')]
    public function testItDoesNotHashSensitiveFilesOrUnsafePaths(string $path): void
    {
        file_put_contents($this->projectDir.'/private.css', 'DEMO private file');
        file_put_contents($this->projectDir.'/public/.env', 'DEMO private configuration');
        file_put_contents($this->projectDir.'/public/index.php', '<?php /* DEMO entrypoint */');
        file_put_contents($this->projectDir.'/public/config.yaml', 'DEMO: configuration');
        file_put_contents($this->projectDir.'/public/css/shell.css', 'body{color:red}');
        self::assertSame(AppVersion::getSemanticVersion(), $this->strategy()->getVersion($path));
    }

    /** @return iterable<string, array{string}> */
    public static function unsafePaths(): iterable
    {
        yield 'parent traversal' => ['../private.css'];
        yield 'nested traversal' => ['css/../../private.css'];
        yield 'absolute traversal' => ['/../private.css'];
        yield 'encoded traversal' => ['%2e%2e/private.css'];
        yield 'encoded separator' => ['css%2f..%2f..%2fprivate.css'];
        yield 'Windows traversal' => ['..\\private.css'];
        yield 'hidden configuration' => ['.env'];
        yield 'PHP entrypoint' => ['index.php'];
        yield 'YAML configuration' => ['config.yaml'];
        yield 'external URL' => ['https://example.invalid/css/shell.css'];
        yield 'protocol-relative URL' => ['//example.invalid/css/shell.css'];
        yield 'null byte' => ["css/shell.css\0"];
        yield 'query string' => ['css/shell.css?file=../private.css'];
        yield 'fragment' => ['css/shell.css#fragment'];
        yield 'directory' => ['css'];
    }

    public function testItDoesNotFollowASymlinkOutsidePublic(): void
    {
        file_put_contents($this->projectDir.'/private.css', 'DEMO private file');
        symlink($this->projectDir.'/private.css', $this->projectDir.'/public/css/link.css');
        self::assertSame(AppVersion::getSemanticVersion(), $this->strategy()->getVersion('css/link.css'));
    }

    public function testItDoesNotFollowADirectorySymlinkOutsidePublic(): void
    {
        new Filesystem()->mkdir($this->projectDir.'/private');
        file_put_contents($this->projectDir.'/private/secret.css', 'DEMO private file');
        symlink($this->projectDir.'/private', $this->projectDir.'/public/linked');
        self::assertSame(AppVersion::getSemanticVersion(), $this->strategy()->getVersion('linked/secret.css'));
    }

    public function testAStaticFilenameCannotExposeTheHashOfASensitiveSymlinkTarget(): void
    {
        file_put_contents($this->projectDir.'/public/index.php', '<?php /* DEMO entrypoint */');
        symlink($this->projectDir.'/public/index.php', $this->projectDir.'/public/css/link.css');
        self::assertSame(AppVersion::getSemanticVersion(), $this->strategy()->getVersion('css/link.css'));
    }

    private function strategy(): AssetVersioningStrategy
    {
        return new AssetVersioningStrategy(DevMode::fromString('0'), new FakeUuidFactory(), KernelProjectDir::fromString($this->projectDir));
    }
}
