use strict;
use warnings;
use Test::More tests => 3;
use Config;
use File::Spec;

my $header = File::Spec->catfile($Config{archlibexp}, 'CORE', 'keywords.h');
ok(-f $header, 'archlib CORE contains keywords.h');

open my $fh, '<', $header or die "open $header: $!";
my $contents = do { local $/; <$fh> };
close $fh or die "close $header: $!";

like($contents, qr/^#define\s+KEY___FILE__\s+\d+/m, 'header defines __FILE__ keyword');
like($contents, qr/^#define\s+KEY_any\s+\d+/m, 'header defines any keyword');
