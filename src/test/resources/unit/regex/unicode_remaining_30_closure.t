use strict;
use warnings;
use utf8;
use Test::More tests => 30;
no warnings qw(experimental::uniprop_wildcards regexp);

sub check_case {
    my ($pattern, $subject, $expected, $id) = @_;
    my $compiled = eval "qr/$pattern/";
    my $error = $@;
    my $matched = !$error && $subject =~ $compiled ? 1 : 0;
    ok(!$error && $matched == $expected, "$id $pattern");
    diag($error) if $error;
}

my $word_body = q{\b{wb}\x{20}\B{wb}\x{20}\B{wb}\x{20}\B{wb}\x{20}\B{wb}\x{20}\b{wb}\x{20}\B{wb}\x{308}\b{wb}};
my @word_ids = qw(20 22 24 26 28);
my @word_modifiers = qw(a aa d u i);
for my $index (0 .. $#word_ids) {
    check_case("(?$word_modifiers[$index]:$word_body)",
        "      \x{308}", 1, "uniprops01:$word_ids[$index]");
}

check_case(q{\p{gc=:(?aa)s:}}, " ", 1, 'uniprops01:40');

my @decomposition_patterns = (
    q{\p{Decomposition_Type=:\ANon_Canonical\z:}},
    q{\p{Decomposition_Type=:\Anoncanonical\z:}},
    q{\p{Dt=:\ANon_Canon\z:}},
    q{\p{Dt=:\Anoncanon\z:}},
);
my @decomposition_ids = (
    [38395, 38396], [38405, 38406], [38417, 38418], [38427, 38428],
);
for my $index (0 .. $#decomposition_patterns) {
    check_case($decomposition_patterns[$index], "\x{1fbf9}", 1,
        "uniprops01:$decomposition_ids[$index][0]");
    check_case($decomposition_patterns[$index], "\x{1fbfa}", 0,
        "uniprops01:$decomposition_ids[$index][1]");
}

my @category_forms = (
    [q{\p{Is_General_Category%s%s}}, 0],
    [q{\p{^Is_General_Category%s%s}}, 1],
    [q{\P{Is_General_Category%s%s}}, 1],
    [q{\P{^Is_General_Category%s%s}}, 0],
);
my @category_subjects = ("\x{1e943}", "\x{1e944}");
my @category_member = (1, 0);
my $category_id = 41547;
for my $assignment ([':', 'l&'], ['= ', 'L&']) {
    for my $subject_index (0 .. $#category_subjects) {
        for my $form (@category_forms) {
            my $pattern = sprintf($form->[0], @$assignment);
            my $expected = $category_member[$subject_index] ^ $form->[1];
            check_case($pattern, $category_subjects[$subject_index], $expected,
                "uniprops01:" . $category_id++);
        }
    }
}
