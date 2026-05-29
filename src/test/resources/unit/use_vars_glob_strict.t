use strict;
use warnings;
use Test::More tests => 2;

{
    package UseVarsGlobStrict;
    use strict;
    use vars qw(*in);

    sub fill {
        %in = (CGI => 'ok');
        return $in{CGI};
    }
}

is UseVarsGlobStrict::fill(), 'ok',
    'use vars qw(*name) predeclares hash slot under strict vars';

{
    package UseVarsGlobStrictLetter;
    use strict;
    use vars qw(*A);

    sub fill {
        $A = 1;
        @A = (2);
        %A = (k => 3);
        return "$A$A[0]$A{k}";
    }
}

is UseVarsGlobStrictLetter::fill(), '123',
    'use vars qw(*A) predeclares scalar, array, and hash slots';
