use strict;
use warnings;
use Test::More tests => 5;

my @warnings;
my $output = 'prefix';
my $missing;
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    $output .= $missing;
}
like(join('', @warnings), qr/Use of uninitialized value.*concatenation/,
    '.= warns when its right operand is undefined');
is($output, 'prefix', '.= keeps the defined left value when its right side is undef');

@warnings = ();
my $undefined_output;
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    $undefined_output .= 'value';
}
is_deeply(\@warnings, [], '.= does not warn for an undefined left operand');

# Exercise warning locations in generated template code.
#line 1 "generated-template.html"
my $template_output = '';
my $template_value;
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    $template_output .= $template_value;
}
like(join('', @warnings), qr/at generated-template\.html line 5\./,
    '.= warning retains a generated source location');

my $fatal_error;
{
    use warnings FATAL => qw(uninitialized);
    my $fatal_output = 'prefix';
    my $fatal_value;
    eval { $fatal_output .= $fatal_value };
    $fatal_error = $@;
}
like($fatal_error, qr/Use of uninitialized value.*concatenation/,
    'fatal uninitialized warning from .= throws');

