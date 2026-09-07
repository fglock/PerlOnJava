use strict;
use warnings;
use Test::More;

my $single = 'left:right';
is($single =~ s/(left):/\1=/, 1, 'single substitution with escaped capture succeeds');
is($single, 'left=right', 'escaped capture expands to capture group rather than an octal byte');

my $boundary = 'Aa';
my $multipart = "--Aa\015\012H: one\015\012\015\012first\015\012"
    . "--Aa\015\012H: two\015\012\015\012second\015\012--Aa--\015\012";
my @parts;
while ($multipart =~ s/
    ^--\Q$boundary\E             \015?\012
    ((?:[^\015\012]+\015\012)* ) \015?\012
    (.*?)                        \015?\012
    (--\Q$boundary\E (--)?       \015?\012)
    /\3/xs) {
    push @parts, $2;
}

is_deeply(\@parts, [qw(first second)],
    'escaped boundary capture permits every multipart section to be decoded');
is($multipart, "--Aa--\015\012", 'final boundary remains after each captured replacement');

done_testing;
