use strict;
use warnings;
use Test::More;

sub captures {
    my ($text, $pattern) = @_;
    return unless $text =~ $pattern;
    return ($&, $^N, $+, join q{|}, map { defined $_ ? $_ : q{} } ($1, $2, $3, $4));
}

my $recursive_named = [ captures('madamimadam', qr/^(?<PAL>(?<CHAR>.)((?&PAL)|.?)\k<CHAR>)$/) ];
is_deeply(
    $recursive_named,
    [ 'madamimadam', 'madamimadam', 'adamimada', 'madamimadam|m|adamimada|' ],
    'recursive named call publishes final closed captures',
);

my $relative_call = [ captures('abc abccba cba', qr/(([abc]+) \g-1)(([abc]+) \g{-1})/) ];
is_deeply(
    $relative_call,
    [ 'abc abccba cba', 'cba cba', 'cba', 'abc abc|abc|cba cba|cba' ],
    'ordinary relative calls retain their final capture iteration',
);

my $define_call = [ captures('aa', qr/(?(DEFINE)(?<A>(?&B)+)(?<B>a))(?&A)/) ];
is_deeply(
    $define_call,
    [ 'aa', undef, undef, '|||' ],
    'DEFINE calls publish captures from their final iteration',
);

my $recursive_backref = [ captures('aba', qr/^(.\1?)(?1)$/) ];
is_deeply(
    $recursive_backref,
    [ 'aba', 'a', 'a', 'a|||' ],
    'recursive backreference retains the final capture',
);

done_testing;
