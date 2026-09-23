use strict;
no warnings;
use Test::More tests => 13;

# Helper subroutine to capture error messages
sub capture_error {
    my ($code) = @_;
    my $error;
    {
        local $@;
        eval $code;
        $error = $@;
    }
    $error =~ s/\n/ /g;
    print "# Error <<< $error >>>\n";
    return $error;
}

# Test cases
my @tests = (
    {
        code => 'my $x = 1 / 0;',
        expected => qr/Illegal division by zero/,
    },
    {
        code => 'my $x = undef; print $x->method;',
        expected => qr/Can't call method "method" on an undefined value/,
    },
    {
        code => 'my $x = "string"; $x->method();',
        expected => qr/\QCan't locate object method "method" via package "string" (perhaps you forgot to load "string"?)/,
    },
    {
        code => 'my $x = []; $x->{key};',
        expected => qr/Not a HASH reference/,
    },
    {
        code => 'my $x = {}; $x->[0];',
        expected => qr/Not an ARRAY reference/,
    },
    {
        code => 'my $x = {}; $x->();',
        expected => qr/Not a CODE reference/,
    },
    {
        code => 'my $x = "string"; $x->{key};',
        expected => qr/Can't use string \("string"\) as a HASH ref/,
    },
    {
        code => '$1++;',
        expected => qr/Modification of a read-only value attempted|^$/,
    },
    {
        code => 'my $x = bareword;',
        expected => qr/Bareword "bareword" not allowed while "strict subs" in use/,
    },
    {
        code => 'my $x = $undeclared_variable;',
        expected => qr/Global symbol "\$undeclared_variable" requires explicit package name/,
    },
    {
        code => 'my @b; my $c = 1; $c ? $a : @b = 123',
        expected => qr/Assignment to both a list and a scalar/,
    },
);

# Run tests
foreach my $test (@tests) {
    my $error = capture_error($test->{code});
    like($error, $test->{expected}, "Error message matches expected pattern");
}

my $loaded_package = bless {}, 'MethodErrorLoaded';
eval { $loaded_package->missing };
like($@, qr/^Can't locate object method "missing" via package "MethodErrorLoaded" at/,
    'a package materialized by bless omits the load hint');

eval { UNIVERSAL->MethodErrorMissing::missing };
like($@, qr/^Can't locate object method "missing" via package "MethodErrorMissing" \(perhaps you forgot to load "MethodErrorMissing"\?\) at/,
    'qualified missing methods use the method lookup diagnostic');

done_testing();
