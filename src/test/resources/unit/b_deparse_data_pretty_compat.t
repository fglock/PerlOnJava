use strict;
use warnings;
use B::Deparse;
use Test::More;

my $code = sub
{
    my $v = shift( @_ );
    print( "Hello world $v\n" );
    return(1);
};
my $deparse = B::Deparse->new;
$deparse->ambient_pragmas(qw(strict warnings));

my $text = $deparse->coderef2text($code);
$text =~ s/^    use (?:strict|warnings);\n//mg;
is(
    $text,
    "{\n    my \$v = shift \@_;\n    print \"Hello world \$v\\n\";\n    return 1;\n}",
    'multiline source is deparsed with normalized built-in calls and indentation',
);

done_testing;
