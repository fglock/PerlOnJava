# Internal helper module for DBI: Exporter wiring, :sql_types /
# :sql_cursor_types / :utils / :profile tags, and the small utility
# functions (neat, neat_list, looks_like_number, data_string_diff,
# data_string_desc, data_diff, dump_results, sql_type_cast, dbi_time).
#
# Lives in its own file so PerlOnJava compiles it to a separate JVM
# class — the combined DBI.pm would otherwise overflow a per-method
# bytecode limit during module load.

package DBI;
use strict;
use warnings;
use Exporter ();

our (@EXPORT, @EXPORT_OK, %EXPORT_TAGS);
@EXPORT    = ();
@EXPORT_OK = qw(%DBI %DBI_methods hash);
%EXPORT_TAGS = (
    sql_types => [ qw(
        SQL_GUID SQL_WLONGVARCHAR SQL_WVARCHAR SQL_WCHAR SQL_BIGINT SQL_BIT
        SQL_TINYINT SQL_LONGVARBINARY SQL_VARBINARY SQL_BINARY SQL_LONGVARCHAR
        SQL_UNKNOWN_TYPE SQL_ALL_TYPES SQL_CHAR SQL_NUMERIC SQL_DECIMAL
        SQL_INTEGER SQL_SMALLINT SQL_FLOAT SQL_REAL SQL_DOUBLE SQL_DATETIME
        SQL_DATE SQL_INTERVAL SQL_TIME SQL_TIMESTAMP SQL_VARCHAR SQL_BOOLEAN
        SQL_UDT SQL_UDT_LOCATOR SQL_ROW SQL_REF SQL_BLOB SQL_BLOB_LOCATOR
        SQL_CLOB SQL_CLOB_LOCATOR SQL_ARRAY SQL_ARRAY_LOCATOR SQL_MULTISET
        SQL_MULTISET_LOCATOR SQL_TYPE_DATE SQL_TYPE_TIME SQL_TYPE_TIMESTAMP
        SQL_TYPE_TIME_WITH_TIMEZONE SQL_TYPE_TIMESTAMP_WITH_TIMEZONE
        SQL_INTERVAL_YEAR SQL_INTERVAL_MONTH SQL_INTERVAL_DAY SQL_INTERVAL_HOUR
        SQL_INTERVAL_MINUTE SQL_INTERVAL_SECOND SQL_INTERVAL_YEAR_TO_MONTH
        SQL_INTERVAL_DAY_TO_HOUR SQL_INTERVAL_DAY_TO_MINUTE
        SQL_INTERVAL_DAY_TO_SECOND SQL_INTERVAL_HOUR_TO_MINUTE
        SQL_INTERVAL_HOUR_TO_SECOND SQL_INTERVAL_MINUTE_TO_SECOND
    ) ],
    sql_cursor_types => [ qw(
        SQL_CURSOR_FORWARD_ONLY SQL_CURSOR_KEYSET_DRIVEN SQL_CURSOR_DYNAMIC
        SQL_CURSOR_STATIC SQL_CURSOR_TYPE_DEFAULT
    ) ],
    utils => [ qw(
        neat neat_list $neat_maxlen dump_results looks_like_number
        data_string_diff data_string_desc data_diff sql_type_cast
        DBIstcf_DISCARD_STRING DBIstcf_STRICT
    ) ],
    profile => [ qw(
        dbi_profile dbi_profile_merge dbi_profile_merge_nodes dbi_time
    ) ],
);
Exporter::export_ok_tags(keys %EXPORT_TAGS);

# ---- utility functions (ported from DBI.pm / DBI::PurePerl) ----

sub looks_like_number {
    my @new = ();
    for my $thing (@_) {
        if (!defined $thing or $thing eq '') {
            push @new, undef;
        }
        else {
            push @new, ($thing =~ /^([+-]?)(?=\d|\.\d)\d*(\.\d*)?([Ee]([+-]?\d+))?$/) ? 1 : 0;
        }
    }
    return (@_ > 1) ? @new : $new[0];
}

sub neat {
    my $v = shift;
    return "undef" unless defined $v;
    my $quote = q{"};
    if (not utf8::is_utf8($v)) {
        return $v if (($v & ~ $v) eq "0"); # is SvNIOK (numeric)
        $quote = q{'};
    }
    my $maxlen = shift || $DBI::neat_maxlen;
    if ($maxlen && $maxlen < length($v) + 2) {
        $v = substr($v, 0, $maxlen - 5);
        $v .= '...';
    }
    $v =~ s/[^[:print:]]/./g;
    return "$quote$v$quote";
}

sub neat_list {
    my ($listref, $maxlen, $sep) = @_;
    $maxlen = 0 unless defined $maxlen;
    $sep = ", " unless defined $sep;
    join($sep, map { neat($_, $maxlen) } @$listref);
}

sub dump_results {
    my ($sth, $maxlen, $lsep, $fsep, $fh) = @_;
    return 0 unless $sth;
    $maxlen ||= 35;
    $lsep   ||= "\n";
    $fh ||= \*STDOUT;
    my $rows = 0;
    my $ref;
    while ($ref = $sth->fetch) {
        print $fh $lsep if $rows++ and $lsep;
        my $str = neat_list($ref, $maxlen, $fsep);
        print $fh $str;
    }
    print $fh "\n$rows rows" . ($DBI::err ? " ($DBI::err: $DBI::errstr)" : "") . "\n";
    $rows;
}

sub data_string_diff {
    my ($a, $b) = @_;
    unless (defined $a and defined $b) {
        return "" if !defined $a and !defined $b;
        return "String a is undef, string b has " . length($b) . " characters" if !defined $a;
        return "String b is undef, string a has " . length($a) . " characters" if !defined $b;
    }
    my @a_chars = (utf8::is_utf8($a)) ? unpack("U*", $a) : unpack("C*", $a);
    my @b_chars = (utf8::is_utf8($b)) ? unpack("U*", $b) : unpack("C*", $b);
    my $i = 0;
    while (@a_chars && @b_chars) {
        ++$i, shift(@a_chars), shift(@b_chars), next
            if $a_chars[0] == $b_chars[0];
        my @desc = map {
            $_ > 255 ? sprintf("\\x{%04X}", $_) :
            chr($_) =~ /[[:cntrl:]]/ ? sprintf("\\x%02X", $_) :
            chr($_)
        } ($a_chars[0], $b_chars[0]);
        foreach my $c (@desc) {
            next unless $c =~ m/\\x\{08(..)}/;
            $c .= "='" . chr(hex($1)) . "'";
        }
        return sprintf "Strings differ at index $i: a[$i]=$desc[0], b[$i]=$desc[1]";
    }
    return "String a truncated after $i characters" if @b_chars;
    return "String b truncated after $i characters" if @a_chars;
    return "";
}

sub data_string_desc {
    my ($a) = @_;
    require bytes;
    my $utf8 = sprintf "UTF8 %s%s",
        utf8::is_utf8($a) ? "on" : "off",
        utf8::valid($a || '') ? "" : " but INVALID encoding";
    return "$utf8, undef" unless defined $a;
    my $is_ascii = $a =~ m/^[\000-\177]*$/;
    return sprintf "%s, %s, %d characters %d bytes",
        $utf8, $is_ascii ? "ASCII" : "non-ASCII",
        length($a), bytes::length($a);
}

sub data_diff {
    my ($a, $b, $logical) = @_;
    my $diff   = data_string_diff($a, $b);
    return "" if $logical and !$diff;
    my $a_desc = data_string_desc($a);
    my $b_desc = data_string_desc($b);
    return "" if !$diff and $a_desc eq $b_desc;
    $diff ||= "Strings contain the same sequence of characters" if length($a);
    $diff .= "\n" if $diff;
    return "a: $a_desc\nb: $b_desc\n$diff";
}

sub sql_type_cast {
    my (undef, $sql_type, $flags) = @_;
    return -1 unless defined $_[0];
    my $cast_ok = 1;
    my $evalret = eval {
        use warnings FATAL => qw(numeric);
        if ($sql_type == DBI::SQL_INTEGER()) { my $d = $_[0] + 0; return 1; }
        elsif ($sql_type == DBI::SQL_DOUBLE())  { my $d = $_[0] + 0.0; return 1; }
        elsif ($sql_type == DBI::SQL_NUMERIC()) { my $d = $_[0] + 0.0; return 1; }
        else { return -2; }
    } or $^W && warn $@;
    return $evalret if defined($evalret) && ($evalret == -2);
    $cast_ok = 0 unless $evalret;
    return 2 if $cast_ok;
    return 0 if $flags & DBI::DBIstcf_STRICT();
    return 1;
}

sub dbi_time { return time(); }

1;
