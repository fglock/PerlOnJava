use strict;
use warnings;
use Test::More;

my $result = eval {
    # Before Perl 5.44 this invalid jump was a deprecation rather than an
    # unconditional error.  Fatalize it only on those older reference Perls.
    BEGIN { warnings->import(FATAL => 'deprecated') if $] < 5.044 }
    sub { goto target; sin do { target: 1 } }->();
    1;
};

ok(!defined($result), 'goto into an expression do block fails');
like($@, qr/Use of "goto" to jump into a construct/,
    'goto reports the construct-entry error');

done_testing();
