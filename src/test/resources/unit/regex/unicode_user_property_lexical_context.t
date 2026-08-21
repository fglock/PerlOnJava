use strict;
use warnings;
no warnings 'experimental::regex_sets';
use Test::More;

our (@atom_modes, @class_modes, @extended_modes, @single_mode);
our ($comment_calls, $inline_comment_calls, $scoped_comment_calls, $quoted_calls);

sub IsInlineAtom {
    push @atom_modes, $_[0] ? 1 : 0;
    return $_[0] ? "0042\n" : "0041\n";
}
sub IsInlineClass {
    push @class_modes, $_[0] ? 1 : 0;
    return $_[0] ? "0042\n" : "0041\n";
}
sub IsInlineExtended {
    push @extended_modes, $_[0] ? 1 : 0;
    return $_[0] ? "0042\n" : "0041\n";
}
sub IsSingleMode {
    push @single_mode, $_[0] ? 1 : 0;
    return "0042\n";
}
sub IsCommentSide { ++$comment_calls; "0043\n" }
sub IsInlineCommentSide { ++$inline_comment_calls; "0043\n" }
sub IsScopedCommentSide { ++$scoped_comment_calls; "0043\n" }
sub IsQuotedSide { ++$quoted_calls; "0043\n" }

my $atom = eval q{qr/^(?i:\p{IsInlineAtom})(?-i:\p{IsInlineAtom})$/};
is($@, '', 'inline-mode atom property compiles');
ok('BA' =~ $atom, 'inline-mode atom callback definitions match their scopes');
is_deeply([sort @atom_modes], [0, 1],
    'atom callback receives both local fold modes exactly once');

my $class = eval q{qr/^(?i:[\p{IsInlineClass}])(?-i:[\p{IsInlineClass}])$/};
is($@, '', 'inline-mode standard-class property compiles');
ok('BA' =~ $class, 'standard-class callbacks match their local scopes');
is_deeply([sort @class_modes], [0, 1],
    'standard-class callback receives both local fold modes exactly once');

my $extended = eval q{qr/^(?i:(?[\p{IsInlineExtended}]))(?-i:(?[\p{IsInlineExtended}]))$/};
is($@, '', 'inline-mode extended-class property compiles');
ok('BA' =~ $extended, 'extended-class callbacks match their local scopes');
is_deeply([sort @extended_modes], [0, 1],
    'extended-class callback receives both local fold modes exactly once');

my $single = eval q{qr/^(?i:\p{IsSingleMode})$/};
is($@, '', 'single inline-mode property compiles');
ok('B' =~ $single, 'single inline-mode callback definition matches');
is_deeply(\@single_mode, [1],
    'preload does not invoke a callback in the enclosing fold mode');

my $comment = eval "qr/(?x:# \\p{IsCommentSide}\nA)/";
is($@, '', 'property-shaped text in an /x comment compiles');
ok('A' =~ $comment, '/x comment does not affect the executable pattern');
is($comment_calls || 0, 0, '/x comment does not invoke a user-property callback');

my $inline_comment = eval q{qr/(?# \p{IsInlineCommentSide})A/};
is($@, '', 'property-shaped text in an inline comment compiles');
ok('A' =~ $inline_comment, 'inline comment does not affect the executable pattern');
is($inline_comment_calls || 0, 0,
    'inline comment does not invoke a user-property callback');

my $scoped_comment = eval "qr/(?x:# \\p{IsScopedCommentSide}\nA)/";
is($@, '', 'property-shaped text in a scoped /x comment compiles');
ok('A' =~ $scoped_comment, 'scoped /x comment is ignored by the matcher');
is($scoped_comment_calls || 0, 0,
    'scoped /x comment does not invoke a user-property callback');

my $quoted = eval q{qr/\Q\p{IsQuotedSide}\E/};
is($@, '', 'property-shaped text in a quoted region compiles');
ok('\\p{IsQuotedSide}' =~ $quoted,
    'quoted property spelling remains literal matcher text');
is($quoted_calls || 0, 0, 'quoted region does not invoke a user-property callback');

done_testing;
