use strict;
use warnings;
use Test::More;

{
    package Local::IsaBase;
    package Local::IsaChild;
    our @ISA = ('Local::IsaBase');
}

my $check = eval q{
    use experimental 'isa';
    sub { $_[0] isa Local::IsaBase }
};

ok(defined($check), 'isa package bareword compiles under strict subs')
    or diag $@;
ok($check->(bless({}, 'Local::IsaChild')), 'isa package bareword checks inheritance');
ok(!$check->(bless({}, 'Local::Other')), 'isa package bareword rejects another class');

done_testing;
