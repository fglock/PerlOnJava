use strict;
use warnings;
use Test::More;
use Carp ();

if (!eval { require Moo; 1 }) {
    plan skip_all => 'Moo is required for the eval-wrapper croak location regression';
}

my $package = 'CarpMooEvalWrapperLocation';
my $code = <<'MOO_CODE';
use Moo;
has attr => (is => 'rwp', lazy => 1, default => 1);
my $obj = $PACKAGE->new;
package Elsewhere;
$obj->attr(5);
MOO_CODE
$code =~ s/\$PACKAGE/$package/g;

my $source = $code;
$source =~ s/\n\z//;
my $expected_line = 1 + ($source =~ tr/\n//);
my $test_sub = eval(
    "sub {\n"
        . "package $package;\n"
        . "#line 1 CarpMooEvalWrapperLocationTest\n"
        . $code
        . "\n}\n"
);
BAIL_OUT("could not compile Moo croak location reproducer: $@") unless $test_sub;

my $error;
{
    local $@;
    eval { $test_sub->(); 1 };
    $error = $@;
}

like(
    $error,
    qr/at CarpMooEvalWrapperLocationTest line \Q$expected_line\E\.?\n?\z/s,
    'Carp::croak reports the generated method call site through the eval wrapper',
) or diag("error: $error");

done_testing();
