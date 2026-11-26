use strict;
use warnings;
use Test::More;

BEGIN {
    plan skip_all => 'tests PerlOnJava builtin lexical imports'
        unless $^X =~ /jperl/;
}

my $warning = '';
{
    local $SIG{__WARN__} = sub { $warning .= shift };
    my $result = eval q{
        use strict;
        use warnings;
        use warnings 'closure';
        no warnings 'experimental::builtin';
        use builtin 'weaken';
        sub captured_weaken { weaken($_[0]) }
        1;
    };
    is($@, '', 'a named sub can use a file-scope imported builtin');
}
is($warning, '', 'a file-scope builtin import does not trigger a closure warning');

done_testing();
