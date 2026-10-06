use Test::More tests => 9;

my $undef = \&CORE::undef;
is($undef->(), undef, 'CORE::undef coderef returns undef');
is_deeply([$undef->()], [undef], 'CORE::undef coderef returns undef in list context');
is(\$undef->(), \undef, 'CORE::undef coderef returns the canonical lvalue');

my $value = 'defined';
$undef->(\$value);
ok(!defined $value, 'CORE::undef coderef clears a scalar reference');

my @values = qw(a b);
$undef->(\@values);
is_deeply(\@values, [], 'CORE::undef coderef clears an array reference');

my %values = (a => 1);
$undef->(\%values);
is_deeply(\%values, {}, 'CORE::undef coderef clears a hash reference');

sub core_undef_target { 1 }
$undef->(\&core_undef_target);
ok(!defined(&core_undef_target), 'CORE::undef coderef clears a named code reference');

eval { $undef->(\my $first, \my $second) };
like($@, qr/^Too many arguments for undef operator at /,
    'CORE::undef coderef reports its Perl arity diagnostic');
eval { $undef->(1) };
like($@, qr/^Type of arg 1 to &CORE::undef must be reference to one of \[\$\@%&\*\] at /,
    'CORE::undef coderef validates its reference argument');
