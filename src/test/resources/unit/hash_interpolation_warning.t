use strict;
use warnings;

my $test = 0;
sub ok {
    my ($pass, $name) = @_;
    ++$test;
    print(($pass ? "ok" : "not ok"), " $test - $name\n");
}

my @warnings;
local $SIG{__WARN__} = sub { push @warnings, @_ };

{
    no warnings;
    use warnings qw(uninitialized);
    my %h;
    my $value = "$h{\1}";
}
ok(@warnings == 1, 'missing reference-key hash element warns once');
ok($warnings[0] =~ /Use of uninitialized value \$h\{"SCALAR\(0x[\da-f]+\)"\} in string/,
   'warning includes hash name and runtime key');

@warnings = ();
{
    no warnings;
    use warnings qw(uninitialized);
    my %h = (present => 'value', empty => undef);
    my $value = "$h{present}";
    my $missing = "$h{missing}";
    my $empty = "$h{empty}";
}
ok(@warnings == 2, 'missing and existing undef elements each warn once');
ok($warnings[0] =~ /Use of uninitialized value \$h\{"missing"\} in string/,
   'literal missing key warning has quoted key context');
ok($warnings[1] =~ /Use of uninitialized value \$h\{"empty"\} in string/,
   'existing undef value warning has quoted key context');

@warnings = ();
{
    no warnings;
    my %h;
    my $value = "$h{missing}";
}
ok(@warnings == 0, 'disabled uninitialized warnings remain silent');

print "1..$test\n";
