use strict;
use warnings;

print "1..10\n";

our ($seen_package, $seen_regex);

"a" =~ do {
    package CallbackPackage;
    qr/(?{ $main::seen_package = __PACKAGE__ })a/;
};
print $seen_package eq 'CallbackPackage'
    ? "ok 1 - qr callback retains its lexical package\n"
    : "not ok 1 - qr callback retains its lexical package ($seen_package)\n";

"a" =~ do {
    use re '/x';
    qr/(?{ $main::seen_regex = qr-- })a/;
};
print "$seen_regex" eq '(?^x:)'
    ? "ok 2 - qr callback retains lexical x modifier\n"
    : "not ok 2 - qr callback retains lexical x modifier ($seen_regex)\n";

"ba" =~ m+b${\do {
    use re '/i';
    qr|(?{ $main::seen_regex = qr-- })a|;
}}+;
print "$seen_regex" eq '(?^i:)'
    ? "ok 3 - interpolated qr callback retains lexical i modifier\n"
    : "not ok 3 - interpolated qr callback retains lexical i modifier ($seen_regex)\n";

{
    use re '/m';
    "a" =~ /(?{ $seen_regex = qr-- })a/;
}

print "$seen_regex" eq '(?^m:)'
    ? "ok 4 - literal callback retains lexical m modifier\n"
    : "not ok 4 - literal callback retains lexical m modifier ($seen_regex)\n";

{
    use re '/x';
    {
        no re '/x';
        $seen_regex = qr--;
    }
    print "$seen_regex" eq '(?^:)'
        ? "ok 5 - no re flags is lexically scoped\n"
        : "not ok 5 - no re flags is lexically scoped ($seen_regex)\n";
    $seen_regex = qr--;
}

print "$seen_regex" eq '(?^x:)'
    ? "ok 6 - outer re flags restore after nested scope\n"
    : "not ok 6 - outer re flags restore after nested scope ($seen_regex)\n";

{
    use re 'eval';
    package RuntimeCallbackPackage;
    "a" =~ /${\'(?{ $main::seen_package = __PACKAGE__ })a'}/;
}
print $seen_package eq 'RuntimeCallbackPackage'
    ? "ok 7 - runtime callback source retains package\n"
    : "not ok 7 - runtime callback source retains package ($seen_package)\n";

{
    use re 'eval', '/m';
    "a" =~ /${\'(?{ $main::seen_regex = qr-- })a'}/;
}
print "$seen_regex" eq '(?^m:)'
    ? "ok 8 - runtime callback source retains lexical modifiers\n"
    : "not ok 8 - runtime callback source retains lexical modifiers ($seen_regex)\n";

{
    use re 'eval';
    my $outer = 41;
    "a" =~ /${\'(?{ $main::seen_regex = ++$outer })a'}/;
    print $outer == 42 && $seen_regex == 42
        ? "ok 9 - runtime callback source shares live lexical cells\n"
        : "not ok 9 - runtime callback source shares live lexical cells ($outer/$seen_regex)\n";
}

my $restored_package = qr/(?{ $seen_package = __PACKAGE__ })/;
"" =~ /$restored_package/;
print $seen_package eq 'main'
    ? "ok 10 - package restores after callback scopes\n"
    : "not ok 10 - package restores after callback scopes ($seen_package)\n";
