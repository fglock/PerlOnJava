use strict;
use warnings;
use Scalar::Util ();
use Test::More;

my $warnings = '';
{
    local $SIG{__WARN__} = sub { $warnings .= join '', @_ };
    eval q{
        no warnings 'syntax';
        sub issue_1655;
        BEGIN { Scalar::Util::set_prototype(\&issue_1655, '_') }
        sub issue_1655 { $_[0] }
    };
}

is $@, '', 'a body can fulfill a forward declaration with a runtime-assigned prototype';
is $warnings, '', 'the syntax warning mask suppresses the prototype mismatch';
is prototype(\&issue_1655), undef, 'the body definition has no inline prototype';
is issue_1655('value'), 'value', 'the defined subroutine remains callable';

done_testing;
