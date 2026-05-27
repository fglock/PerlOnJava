use strict;
use warnings;
use Config;
use Cwd qw(getcwd);
use File::Temp qw(tempdir);
use Test::More tests => 4;

my $dir = tempdir(CLEANUP => 1);
my $name = 'poj-system-path-child';
my $path = "$dir/$name";
local $ENV{PATHEXT} = '.COM;.EXE;.BAT;.CMD' if $^O eq 'MSWin32';

if ($^O eq 'MSWin32') {
    $path .= '.bat';
    open my $fh, '>', $path or die "open $path: $!";
    print {$fh} "\@echo off\r\n";
    print {$fh} "if \"%~1\"==\"\" (\r\n";
    print {$fh} "  echo path-ok\r\n";
    print {$fh} "  exit /b 0\r\n";
    print {$fh} ")\r\n";
    print {$fh} "exit /b %~1\r\n";
    close $fh or die "close $path: $!";
} else {
    open my $fh, '>', $path or die "open $path: $!";
    print {$fh} "#!$^X\nprint \"path-ok\\n\" unless \@ARGV;\nexit(\$ARGV[0] || 0);\n";
    close $fh or die "close $path: $!";
    chmod 0755, $path or die "chmod $path: $!";
}

local $ENV{PATH} = join $Config{path_sep}, $dir, ($ENV{PATH} || ());

my $status = system $name, 7;
is($status & 0xff, 0, 'system LIST finds executable through PATH');
is($status >> 8, 7, 'system LIST preserves child exit status');

my $out = `$name`;
$out =~ s/\r\n/\n/g if $^O eq 'MSWin32';
is($?, 0, 'simple backticks find executable through PATH');
is($out, "path-ok\n", 'simple backticks capture child output');
