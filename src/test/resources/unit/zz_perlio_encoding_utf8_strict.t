use strict;
use warnings;
use Test::More tests => 2;
use File::Temp qw/tempfile/;

my ($seed, $filename) = tempfile();
close $seed;

open my $encoded, '>:encoding(utf-8-strict)', $filename
    or die "open encoded output: $!";
ok((print {$encoded} "\x{263a}"),
    ':encoding(utf-8-strict) accepts Perl Encode canonical name');
close $encoded;

open my $raw, '<:raw', $filename or die "open raw input: $!";
local $/;
my $octets = <$raw>;
close $raw;
is(unpack('H*', $octets), 'e298ba',
    'utf-8-strict PerlIO layer writes UTF-8 octets');
