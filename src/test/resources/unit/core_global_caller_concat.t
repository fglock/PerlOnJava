use Test::More tests => 2;

BEGIN {
    no warnings 'redefine';
    *CORE::GLOBAL::caller = sub (;$) {
        my ($height) = @_;
        $height++;
        my @caller = CORE::caller($height);
        return wantarray ? @caller : $caller[0];
    };
}

my $value = eval q{ caller.'::' };
is($@, '', 'optional caller override compiles before concatenation');
is($value, 'main::', 'concatenation receives zero-argument caller result');
