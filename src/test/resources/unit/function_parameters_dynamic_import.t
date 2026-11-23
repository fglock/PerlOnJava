use strict;
use warnings;
use Test::More;
{
    package Wrapper;
    use Function::Parameters ();
    sub shazam { Function::Parameters->import(@_); }
}
BEGIN { Wrapper::shazam; }
ok fun ($x) { $x }->(1);
{
    package Cu::Ba;
    BEGIN { Wrapper::shazam { gorn => 'function_lax' }; }
    gorn wooden ($gorn) { !$gorn }
}
ok Cu::Ba::wooden;
done_testing;
