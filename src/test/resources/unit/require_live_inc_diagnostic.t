use strict;
use warnings;
use Test::More;

my $missing = 'PerlOnJava::UAT::MissingModule';
{
    local @INC = ('../lib', 'jar:PERL5LIB');
    eval { require $missing; 1 };
    like(
        $@,
        qr/\@INC entries checked: \.\.\/lib jar:PERL5LIB/,
        'missing module diagnostic includes the bundled path in live @INC',
    );
}

done_testing;
