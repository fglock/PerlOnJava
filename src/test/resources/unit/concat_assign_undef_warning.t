use strict;
use warnings;

print "1..2\n";

sub TIESCALAR { my $value; bless \$value, shift }
sub FETCH { ${$_[0]} }
sub STORE { ${$_[0]} = $_[1] }

for my $tied (0, 1) {
    my $warning = '';
    local $SIG{__WARN__} = sub { $warning .= $_[0] };
    my $value;
    tie $value, __PACKAGE__ if $tied;
    $value .= 'x';
    my $number = $tied + 1;
    print $value eq 'x' && $warning eq ''
        ? "ok $number - undef concat assignment does not warn\n"
        : "not ok $number - undef concat assignment does not warn ($warning)\n";
}
