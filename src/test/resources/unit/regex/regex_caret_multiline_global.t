use strict;
use warnings;
use Test::More;

###############################################################################
# Tests for ^ in /m mode under /g
#
# Regression test for a bug where Matcher.region(...) inside the global-match
# advancement loop was called with useAnchoringBounds=true (Java's default),
# making ^ match at the artificial region boundary even when that offset is
# not preceded by \n in the input string. Symptom:
#
#     "ab\ncd\n" =~ /^(.*)/mg   yielded 4 matches in jperl, 2 in perl.
#
# This in turn broke the very common "find minimum indent" idiom used by
# Pod::Html::Util::trim_leading_whitespace and many other modules.
###############################################################################

# Bare anchor walking
{
    my @m = "ab\ncd\n" =~ /^(.*)/mg;
    is(scalar(@m), 2, 'm/^(.*)/mg with trailing \n: count');
    is_deeply(\@m, ['ab', 'cd'], 'm/^(.*)/mg with trailing \n: items');
}

{
    my @m = "ab\ncd" =~ /^(.*)/mg;
    is(scalar(@m), 2, 'm/^(.*)/mg without trailing \n: count');
    is_deeply(\@m, ['ab', 'cd'], 'm/^(.*)/mg without trailing \n: items');
}

{
    my @m = "" =~ /^(.*)/mg;
    is(scalar(@m), 1, 'm/^(.*)/mg on empty string: one empty match');
    is_deeply(\@m, [''], 'm/^(.*)/mg on empty string: items');
}

{
    my @m = "\n\n" =~ /^(.*)/mg;
    is(scalar(@m), 2, 'm/^(.*)/mg on "\n\n": count');
    is_deeply(\@m, ['', ''], 'm/^(.*)/mg on "\n\n": items');
}

# Anchor + at-least-one body (no zero-width temptation)
{
    my @m = "ab\ncd\n" =~ /^(.+)/mg;
    is(scalar(@m), 2, 'm/^(.+)/mg: count');
    is_deeply(\@m, ['ab', 'cd'], 'm/^(.+)/mg: items');
}

# Anchor + zero-or-more spaces + non-newline (Pod::Html::Util idiom)
{
    my $s = "    use Foo;\n    bar();\n";
    my @m = $s =~ /^( *)./mg;
    is(scalar(@m), 2, 'leading-space capture: count');
    is_deeply(\@m, ['    ', '    '], 'leading-space capture: items');
}

# Mixed indents, real-world: the indent we extract should be the minimum
{
    my $s = "    a\n  b\n        c\n";
    my @indents = sort($s =~ /^( *)./mg);
    is($indents[0], '  ', 'minimum indent over multiple lines');
}

# Substitution variant: ^ in /m + /g must strip exactly the line-start prefix
{
    my $s = "    a\n    b\n";
    (my $t = $s) =~ s/^    //mg;
    is($t, "a\nb\n", 's/^    //mg strips leading 4-space prefix on every line');
}

# End-anchor counterpart (parity with ^)
{
    my @m = "ab\ncd\n" =~ /(.*)$/mg;
    # Perl matches "ab" at end-of-line-1, "cd" at end-of-line-2, and "" at
    # end-of-string. We accept either Perl's exact list or, defensively, the
    # same multiset minus duplicates. Lock to what perl actually produces:
    is_deeply(\@m, ['ab', '', 'cd', '', ''], 'm/(.*)$/mg yields perl-compatible list')
        or diag explain \@m;
}

done_testing();
