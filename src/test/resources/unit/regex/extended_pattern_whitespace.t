use strict;
use warnings;
use utf8;
use Test::More;

sub compile_with_space {
    my ($code_point) = @_;
    my $space = chr($code_point);
    utf8::upgrade($space);
    my @warnings;
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    my $regex = eval "qr/a${space}b/x";
    return ($regex, $@, \@warnings);
}

for my $case (
    [0x0009, 'tab'],
    [0x000A, 'line feed'],
    [0x000B, 'vertical tab'],
    [0x000C, 'form feed'],
    [0x000D, 'carriage return'],
    [0x0020, 'space'],
    [0x0085, 'next line'],
    [0x200E, 'left-to-right mark'],
    [0x200F, 'right-to-left mark'],
    [0x2028, 'line separator'],
    [0x2029, 'paragraph separator'],
) {
    my ($regex, $error, $warnings) = compile_with_space($case->[0]);
    is($error, '', "$case->[1] compiles under /x");
    is_deeply($warnings, [], "$case->[1] is quiet under /x");
    ok('ab' =~ $regex, "$case->[1] is ignored under /x");
}

for my $case (
    [0x00A0, 'no-break space'],
    [0x1680, 'ogham space mark'],
) {
    my ($regex, $error, $warnings) = compile_with_space($case->[0]);
    is($error, '', "$case->[1] compiles under /x");
    is_deeply($warnings, [], "$case->[1] is quiet under /x");
    ok('ab' !~ $regex, "$case->[1] remains a literal under /x");
}

done_testing;
