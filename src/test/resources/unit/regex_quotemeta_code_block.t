use strict;
use warnings;

print "1..2\n";

print "ok 1 - quoted regex code block opener is literal\n"
    if '(?{1})' =~ /^\Q(?{1})\E$/;

print "ok 2 - quote region may end inside code-block-shaped text\n"
    if '(?{1})' =~ /^\Q(?{\E1\}\)$/;
