use strict;
use warnings;
use Test::More tests => 7;

'outer-one:outer-two:outer-three' =~ /(outer-one):(outer-two):(outer-three)/;

{
    local($1, $2, $3);
    'first-one:first-two:first-three' =~ /(first-one):(first-two):(first-three)/;
    is("$1/$2/$3", 'first-one/first-two/first-three',
       'multiple localized captures receive every group');

    {
        local($1, $2, $3);
        'nested-one:nested-two:nested-three' =~ /(nested-one):(nested-two):(nested-three)/;
        is("$1/$2/$3", 'nested-one/nested-two/nested-three',
           'nested localized captures receive every group');
    }

    is("$1/$2/$3", 'first-one/first-two/first-three',
       'nested localization restores the enclosing capture state');
}

is("$1/$2/$3", 'outer-one/outer-two/outer-three',
   'localization restores the caller capture state');

my $text = '=?US-ASCII?Q?Keith_Moore?=';
{
    local($1, $2, $3);
    pos($text) = 0;
    $text =~ m{\G=\?([^?]*)\?([bq])\?([^?]+)\?=}xgi or die 'no match';
    is($1, 'US-ASCII', 'MIME charset capture survives localization');
    is(lc($2), 'q', 'MIME encoding capture survives localization');
    is($3, 'Keith_Moore', 'MIME payload capture survives localization');
}
