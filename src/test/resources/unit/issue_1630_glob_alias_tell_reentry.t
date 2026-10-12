use strict;
use warnings;
use Test::More;

{
    package GlobAliasTellCapture;
    use Tie::Handle;
    sub TIEHANDLE { bless {}, shift }
    sub TELL { tell main::ORIGINAL }
    sub PRINT { }
}

{
    no strict 'refs';
    *ORIGINAL = *STDERR;
    tie *CAPTURE, 'GlobAliasTellCapture';
}

{
    no strict 'refs';
    local *STDERR = *CAPTURE;
    is(tell STDERR, 0,
        'tied TELL reads the original glob through its alias during localization');
}

done_testing;
