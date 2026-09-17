package Devel::LexicalSubGoto;

sub import {
    no warnings 'redefine';
    *DB::sub = sub { goto $DB::sub };
    *DB::goto = sub { $_ = $DB::sub };
}

1;
