use strict;
use warnings;
use Test::More tests => 6;
use File::Temp qw(tempfile);

my ($fh, $path) = tempfile();
print {$fh} "first\nsecond\n" or die "write tempfile: $!";
close $fh or die "close tempfile: $!";

open my $input, '<', $path or die "open tempfile: $!";
my $line = '';
do {
    local $/ = \1;
    my $char = <$input>;
    $line .= $char if defined $char;
} until $line =~ m/((\015|\012|\025|\n)$)/s;
is($1, "\n", 'capture from successful do-until condition remains available');
is($line, "first\n", 'one-byte reads stop after matching the line ending');

{
    local $/ = "\n";
    is(<$input>, "second\n", 'ordinary line reads resume after one-byte reads');
}

my $value = "abc\n";
do { } until $value =~ m/((\015|\012|\025|\n)$)/s;
is($1, "\n", 'nested capture from an empty do-until body is retained');

$value = 'no newline';
my $matched = $value =~ m/((\015|\012|\025|\n)$)/s;
ok(!$matched, 'negative control does not match');
is($1, "\n", 'failed match leaves the previous successful captures intact');

close $input;
unlink $path;
