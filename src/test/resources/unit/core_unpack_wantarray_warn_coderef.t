use Test::More tests => 6;

my $unpack = \&CORE::unpack;
local $_ = 'abcd';
is($unpack->('H*'), '61626364',
    'CORE::unpack coderef defaults its data argument to $_');

my $wantarray = \&CORE::wantarray;
*mywantarray = $wantarray;
my $context;
my $context_probe = sub {
    $context = qw[void scalar list][&mywantarray + defined mywantarray()];
};
() = &$context_probe;
is($context, 'list', 'CORE::wantarray coderef sees list context of its caller');
scalar &$context_probe;
is($context, 'scalar', 'CORE::wantarray coderef sees scalar context of its caller');
&$context_probe;
is($context, 'void', 'CORE::wantarray coderef sees void context of its caller');

my $warn = \&CORE::warn;
my $warning;
{
    local $SIG{__WARN__} = sub { $warning = shift };
    is($warn->('coderef warning'), 1, 'CORE::warn coderef returns true');
}
like($warning, qr/^coderef warning at .* line \d+\.\n$/,
    'CORE::warn coderef reports its call site to the warning handler');
