use strict;
use warnings;

use File::Spec;
use FindBin;
use Test::More;

my $root = File::Spec->rel2abs(
    File::Spec->catdir($FindBin::Bin, '..', '..', '..'));
my $benchmark = File::Spec->catfile($root, 'dev', 'regex', 'tools',
    'regex_benchmark.pl');
local $ENV{REGEX_IMPLEMENTATION_SOURCE_COMMIT} = '1' x 40;
local $ENV{REGEX_IMPLEMENTATION_JAR_SHA256} = '2' x 64;

my $output = qx{$^X "$benchmark" 2>&1};
is($? >> 8, 0, 'canonical ordinary-regex benchmark runs on system Perl');
my @metrics = grep { /^REGEX_IMPLEMENTATION_REGEX_PERFORMANCE\b/ } split /\r?\n/, $output;
is(scalar(@metrics), 1, 'benchmark emits exactly one metric line');
like($metrics[0], qr/\bchecksum=135a355df10cd13cd6bb7eb074e4aaf326b61057ab83753423033a50da258458\b/,
    'benchmark emits the stable semantic checksum');
like($metrics[0], qr/\bjar_sha256=@{['2' x 64]}\b/,
    'benchmark binds the supplied JAR identity');
like($metrics[0], qr/\bsource_commit=@{['1' x 40]}\b/,
    'benchmark binds the supplied source identity');
unlike($output, qr/(?:warning|uninitialized|failed)/i,
    'benchmark emits no diagnostic noise');

done_testing;
