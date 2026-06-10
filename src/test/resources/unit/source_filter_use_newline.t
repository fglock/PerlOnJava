#!/usr/bin/env perl
use strict;
use warnings;
use Test::More tests => 1;
use File::Spec;
use File::Temp qw(tempdir);

{
    package NewlineFilter;
    use Filter::Util::Call;
    our $first_chunk;

    sub import {
        filter_add(sub {
            my $status = filter_read();
            $first_chunk = $_ if !defined $first_chunk;
            return $status;
        });
    }
}
$INC{'NewlineFilter.pm'} = __FILE__;

my $tmp = tempdir(CLEANUP => 1);
my $pm = File::Spec->catfile($tmp, 'NewlineFilterTarget.pm');
open my $fh, '>', $pm or die "open $pm: $!";
print {$fh} <<'EOPM';
package NewlineFilterTarget;
use NewlineFilter;
# first filtered line
1;
EOPM
close $fh;

local @INC = ($tmp, @INC);
require NewlineFilterTarget;

like(
    $NewlineFilter::first_chunk,
    qr/\A# first filtered line\n/,
    'source filter installed by use starts after the use statement newline'
);
