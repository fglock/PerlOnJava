use strict;
use warnings;
use Test::More;

BEGIN { warnings->import(FATAL => 'deprecated') if $] < 5.044 }

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

my @construct_entries = (
    q^sub { goto BAD; $#{; do { BAD: \@_ } } }->()^,
    q^sub { goto BAD; prototype \&{; do { BAD: sub ($) {} } } }->()^,
    q^sub { goto BAD; ref do { BAD: [] } }->()^,
    q^sub { goto BAD; defined undef ${; do { BAD: \(my $foo = "foo") } } }->()^,
    q^sub { goto BAD; study ++${; do { BAD: \(my $foo = "foo") } } }->()^,
    q^sub { goto BAD; ~-!${; do { BAD: \(my $foo = 0) } }++ }->()^,
    q^sub { goto BAD; %{; do { BAD: +{1..2} } } }->()^,
);
for my $index (0 .. $#construct_entries) {
    my $ok = eval $construct_entries[$index];
    ok(!$ok, "construct-entry jump $index fails");
    like($@, qr/Use of "goto" to jump into a construct/,
        "construct-entry jump $index has the Perl diagnostic");
}

done_testing();
