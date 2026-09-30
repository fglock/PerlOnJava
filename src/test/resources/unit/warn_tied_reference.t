use strict;
use warnings;
use Test::More tests => 4;

{
    package WarnTiedReference::EmptyString;
    use overload '""' => sub { '' }, fallback => 1;
}
{
    package WarnTiedReference::TiedScalar;
    sub TIESCALAR { bless { value => $_[1] }, $_[0] }
    sub FETCH { $_[0]->{value} }
}

my $object = bless [], 'WarnTiedReference::EmptyString';
tie my $message, 'WarnTiedReference::TiedScalar', $object;
my $received;
{
    local $SIG{__WARN__} = sub { $received = $_[0] };
    warn $message;
}
isa_ok($received, 'WarnTiedReference::EmptyString',
    '__WARN__ receives the referenced value fetched from a tied scalar');

my $joined;
{
    local $SIG{__WARN__} = sub { $joined = $_[0] };
    warn 'foo', "bar\n";
}
is($joined, "foobar\n", 'warn concatenates all arguments before adding location text');

my $stderr = '';
open my $capture, '>', \$stderr or die "open scalar stderr: $!";
{
    local *STDERR = $capture;
    local $SIG{__WARN__} = 'DEFAULT';
    warn [];
}
like($stderr, qr/^ARRAY\(0x[\da-f]+\) at .* line \d+\.\n$/,
    'default warn stringifies a reference and adds its source location');

my $wide_stderr = '';
open my $wide_capture, '>', \$wide_stderr or die "open scalar stderr: $!";
{
    local *STDERR = $wide_capture;
    local $SIG{__WARN__} = 'DEFAULT';
    warn chr 300;
}
like($wide_stderr, qr/^Wide character in warn .*\n/s,
    'default warn reports a wide character on a byte handle');
