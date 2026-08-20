use strict;
use warnings;
use Test::More tests => 8;

sub compile_warnings {
    my ($source) = @_;
    my @warnings;
    {
        local $SIG{__WARN__} = sub { push @warnings, @_ };
        my $compiled = eval "qr/$source/";
        ok($compiled, "$source compiles");
    }
    return @warnings;
}

my @word_boundary = compile_warnings('\\b*');
is(scalar @word_boundary, 1, 'unbounded word boundary emits one warning');
like($word_boundary[0],
     qr/\\b\* matches null string many times.*m\/\\b\* <-- HERE \/ at/s,
     'word-boundary warning carries source and end position');

my @lookahead = compile_warnings('(?=a)+');
is(scalar @lookahead, 1, 'unbounded lookahead emits one warning');
like($lookahead[0],
     qr/\(\?=a\)\+ matches null string many times.*\(\?=a\)\+ <-- HERE \/ at/s,
     'lookahead warning carries source and end position');

my @bounded = compile_warnings('\\b?');
is(scalar @bounded, 0, 'bounded zero-width assertion remains quiet');
