use strict;
use warnings;
use Test::More;

my $nested_parentheses = qr{
    (?<nested> \( (?: [^()]++ | (?&nested)++ )*+ \) )
}x;

my $source = "sub autosplit_target {\n";
ok(
    $source =~ /^sub\s+([\w:]+)(\s*(?:\(.*?\))?(?:$nested_parentheses)?)/,
    'recursive interpolated pattern matches without its optional branch',
);
is($1, 'autosplit_target', 'unnamed capture remains numbered beside named recursion');
is($2, ' ', 'second unnamed capture retains its Perl group number');
ok(!defined $+{nested}, 'optional named recursive capture did not participate');

done_testing;
