use strict;
use warnings;
use File::Temp qw(tempdir);
use Test::More tests => 4;

my $dir = tempdir(CLEANUP => 1);
open my $zero, '>', "$dir/0" or die "create false filename: $!";
close $zero;
opendir my $dh, $dir or die "opendir $dir: $!";

my $seen = 0;
while (my $name = readdir($dh)) {
    $seen++ if $name eq '0';
}
is($seen, 1, 'while assignment tests readdir for definedness');

rewinddir($dh);
$seen = 0;
my %entries;
while ($entries{$seen} = readdir($dh)) {
    $seen++ if $entries{$seen} eq '0';
}
is($seen, 1, 'while hash assignment tests readdir for definedness');

rewinddir($dh);
$seen = 0;
$_ = 'sentinel';
while (readdir($dh)) {
    $seen++ if $_ eq '0';
}
is($seen, 1, 'bare while readdir tests the returned value for definedness');

rewinddir($dh);
$seen = 0;
$_ = '';
do {
    $seen++ if $_ eq '0';
} while (readdir($dh));
is($seen, 1, 'do-while readdir tests the returned value for definedness');

closedir $dh;
